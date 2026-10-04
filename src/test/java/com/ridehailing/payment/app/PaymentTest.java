package com.ridehailing.payment.app;

import static com.ridehailing.support.TestRides.asApi;
import static com.ridehailing.support.TestRides.asWorker;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.payment.db.AttemptRepository;
import com.ridehailing.payment.db.AttemptRepository.AttemptRow;
import com.ridehailing.payment.db.ChargeRepository;
import com.ridehailing.payment.db.ChargeRepository.ChargeRow;
import com.ridehailing.payment.db.ChargeRepository.NewCharge;
import com.ridehailing.payment.db.ProviderCallRepository;
import com.ridehailing.payment.db.RefundRepository;
import com.ridehailing.payment.db.RefundRepository.RefundRow;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.Transactions;
import com.ridehailing.platform.outbox.EventDelivery;
import com.ridehailing.ride.app.DriverCommands;
import com.ridehailing.rider.app.RiderProfiles;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Ids;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestRides.AssignedRide;
import com.ridehailing.support.TestUsers.TestUser;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A city with prices, riders with mock cards, rides taken to the point a test needs, and the executor driven step by
 * step. Other tests' open attempts and refunds are closed first, so the executor works on this test's alone.
 */
abstract class PaymentTest extends IntegrationTest {

    static final String WEBHOOKS = "/v1/webhooks/payments/mock";

    @Autowired
    TestCities cities;

    @Autowired
    TestPrices prices;

    @Autowired
    TestRides rides;

    @Autowired
    RiderProfiles profiles;

    @Autowired
    DriverCommands drivers;

    @Autowired
    EventDelivery events;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockPaymentProvider mock;

    @Autowired
    ChargeRepository charges;

    @Autowired
    AttemptRepository attempts;

    @Autowired
    RefundRepository refunds;

    @Autowired
    ProviderCallRepository calls;

    @Autowired
    Outcomes outcomes;

    @Autowired
    Transactions transactions;

    @Autowired
    PaymentProperties properties;

    /** The application's executor, with a circuit of this test's: timeouts in earlier tests don't open it. */
    PaymentExecutor executor;

    TestCity city;

    @BeforeEach
    void setUpCity() {
        executor = executorWith(mock, properties);
        newCity();
        for (String table : List.of("payment.charge_attempts", "payment.refunds")) {
            jdbc.sql("UPDATE " + table + """
                     SET status = 'FAILED', failure_code = 'ABANDONED_BY_TEST', lease_until = NULL,
                        next_check_at = NULL, completed_at = now()
                    WHERE status IN ('PENDING', 'IN_FLIGHT', 'UNKNOWN')
                    """).update();
        }
    }

    /** A city of the test's own, with prices; a race repetition that books rides takes a new one each time. */
    void newCity() {
        city = cities.create("MINI");
        prices.price(city.id(), "MINI");
    }

    /** A rider whose default method is a card with this mock token. */
    TestUser riderWith(String token) {
        TestUser rider = rides.rider("Rider");
        profiles.setDefaultPaymentMethod(rider.id(), card(rider, token));
        return rider;
    }

    UUID card(TestUser rider, String token) {
        return profiles.addPaymentMethod(rider.id(), "CARD", token, "Visa •• 4242").id();
    }

    AssignedRide assigned(TestUser rider) {
        GeoPoint pickup = city.at(0.1, 0.1);
        return rides.assigned(city, pickup, pickup, rider);
    }

    /** Booked with the rider's default method, completed by the driver, its events delivered to the consumers. */
    AssignedRide completed(TestUser rider) {
        AssignedRide ride = assigned(rider);
        finish(ride);
        return ride;
    }

    /** Arrives, starts and completes the ride as its driver, then delivers its events. */
    void finish(AssignedRide ride) {
        asApi(() -> {
            drivers.arrive(ride.driver().id(), ride.id());
            drivers.start(ride.driver().id(), ride.id(), ride.pin());
            return drivers.complete(ride.driver().id(), ride.id());
        });
        deliver(ride);
    }

    /** The ride's events to the consumers, as the relay would deliver them. */
    void deliver(AssignedRide ride) {
        asWorker(() -> {
            events.deliverAll(ride.id());
            return null;
        });
    }

    /** Cancelled by the rider after the free window (a 5,000-paise fee, 1,000 commission), events delivered. */
    AssignedRide cancelledWithFee(TestUser rider) {
        AssignedRide ride = assigned(rider);
        jdbc.sql("UPDATE ride.rides SET assigned_at = assigned_at - interval '121 seconds' WHERE id = :id")
                .param("id", ride.id()).update();
        HttpResponse<String> cancelled = postJson("/v1/rides/" + ride.id() + "/cancel",
                Map.of("Authorization", rider.authorization(), Idempotency.HEADER, UUID.randomUUID().toString()),
                "{}");
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        deliver(ride);
        return ride;
    }

    ChargeRow charge(UUID rideId, String purpose) {
        return jdbc.sql("SELECT id FROM payment.charges WHERE ride_id = :rideId AND purpose = :purpose")
                .param("rideId", rideId).param("purpose", purpose)
                .query(UUID.class).optional()
                .flatMap(charges::find)
                .orElseThrow(() -> new AssertionError("No " + purpose + " charge for ride " + rideId));
    }

    ChargeRow fare(AssignedRide ride) {
        return charge(ride.id(), "FARE");
    }

    ChargeRow reread(ChargeRow charge) {
        return charges.find(charge.id()).orElseThrow();
    }

    List<AttemptRow> attemptsOf(ChargeRow charge) {
        return attempts.ofCharges(List.of(charge.id()));
    }

    AttemptRow attempt(ChargeRow charge) {
        return attemptsOf(charge).getLast();
    }

    List<RefundRow> refundsOf(ChargeRow charge) {
        return refunds.ofCharge(charge.id());
    }

    /** Runs sends until none is left, as the worker does; answers how many. */
    int send() {
        return asWorker(() -> {
            int sent = 0;
            while (executor.sendNext()) {
                sent++;
            }
            return sent;
        });
    }

    /** Runs due checks until none is left, as the worker does; answers how many. */
    int check() {
        return asWorker(() -> {
            int checked = 0;
            while (executor.checkNext()) {
                checked++;
            }
            return checked;
        });
    }

    /**
     * A {@code PENDING} charge of 10,000 paise (2,000 commission) with its first attempt, as the consumer creates one,
     * for a ride that exists only here: races and limits need many charges, faster than rides can make them.
     */
    ChargeRow pendingCharge(UUID riderId, UUID driverId, String purpose, String token) {
        return transactions.execute(() -> {
            UUID methodId = UUID.randomUUID();
            ChargeRow charge = charges.insert(new NewCharge(Ids.newId(), Ids.newId(), riderId, driverId, city.id(),
                    purpose, 10_000, 2_000, "INR", "CARD", methodId, "PENDING", null)).orElseThrow();
            attempts.insert(Ids.newId(), charge.id(), mock.name(), methodId, token);
            return charge;
        });
    }

    /** A fare of 10,000 paise paid by card through the mock. */
    ChargeRow paidCharge() {
        ChargeRow charge = pendingCharge(UUID.randomUUID(), UUID.randomUUID(), "FARE", "tok_ok");
        send();
        ChargeRow paid = reread(charge);
        assertThat(paid.status()).isEqualTo("SUCCEEDED");
        return paid;
    }

    /** An executor in front of another provider, with a circuit of its own. */
    PaymentExecutor executorWith(PaymentProvider provider, PaymentProperties settings) {
        return new PaymentExecutor(calls, attempts, refunds, outcomes, provider, new ProviderCircuit(settings),
                transactions, settings);
    }

    /** Makes the attempt's or refund's next check due now. */
    void dueNow(String table, UUID id) {
        jdbc.sql("UPDATE " + table + " SET next_check_at = now() WHERE id = :id").param("id", id).update();
    }

    /** Moves the attempt's or refund's send back, as if that much time had passed (LLD §17.4). */
    void sentAgo(String table, UUID id, Duration ago) {
        jdbc.sql("UPDATE " + table + " SET sent_at = now() - make_interval(secs => :s) WHERE id = :id")
                .param("s", ago.toSeconds()).param("id", id).update();
    }

    /** A webhook body as the provider's {@code PaymentWebhook} schema describes it. */
    static String webhook(String eventId, String type, UUID key, long amountPaise, String failureCode) {
        String status = type.endsWith(".succeeded") ? "succeeded" : "failed";
        return """
                {"id": "%s", "type": "%s", "created_at": "%s", "data": {"idempotency_key": "%s", \
                "provider_reference": "pay_%s", "status": "%s", "amount_paise": %d%s}}"""
                .formatted(eventId, type, Instant.now(), key, key, status, amountPaise,
                        failureCode == null ? "" : ", \"failure_code\": \"" + failureCode + "\"");
    }

    /** Posts the body signed now with the provider's secret. */
    HttpResponse<String> postWebhook(String body) {
        return postWebhook(body, mock.sign(Instant.now(), body.getBytes(StandardCharsets.UTF_8)));
    }

    HttpResponse<String> postWebhook(String body, String signature) {
        return postJson(WEBHOOKS, signature == null ? Map.of() : Map.of("X-Signature", signature), body);
    }

    /** {@code POST /v1/riders/me/dues/pay} with a new idempotency key. */
    HttpResponse<String> payDues(TestUser rider, String body) {
        return payDues(rider, body, UUID.randomUUID().toString());
    }

    HttpResponse<String> payDues(TestUser rider, String body, String key) {
        return postJson("/v1/riders/me/dues/pay", Map.of("Authorization", rider.authorization(),
                Idempotency.HEADER, key), body);
    }

    /** Invariant violations in this test's city, I7 included. */
    List<String> violations() {
        return rides.violations(city.id());
    }
}

package com.ridehailing.payment.app;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.payment.db.ChargeRepository.ChargeRow;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.support.RaceRunner;
import com.ridehailing.support.TestRides.AssignedRide;
import com.ridehailing.support.TestUsers.TestUser;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Rider dues (FR-PY3, FR-R4, LLD §11.7, §11.10): they block booking until paid with a card or UPI method. */
class DuesTests extends PaymentTest {

    private static final String DUES = "/v1/riders/me/dues";
    private static final String PAY = "/v1/riders/me/dues/pay";

    @Test
    void aDeclinedFareBlocksTheNextBookingUntilThePaymentSucceeds() {
        TestUser rider = riderWith("tok_decline");
        AssignedRide ride = completed(rider);
        send();
        ChargeRow declined = fare(ride);

        JsonNode dues = assertAnswered("GET", DUES, getAs(rider.authorization(), DUES), 200);
        assertThat(dues.get("total").get("amount_paise").asLong()).isEqualTo(declined.amountPaise());
        assertThat(dues.get("charges")).singleElement().satisfies(charge -> {
            assertThat(charge.get("id").asString()).isEqualTo(declined.id().toString());
            assertThat(charge.get("status").asString()).isEqualTo("FAILED");
            assertThat(charge.get("failure_code").asString()).isEqualTo("DECLINED");
        });
        UUID quote = quote(rider);
        JsonNode refused = assertProblem("POST", "/v1/rides", book(rider, quote), 409, "DUES_OUTSTANDING");
        assertThat(refused.get("dues").get("amount_paise").asLong()).isEqualTo(declined.amountPaise());
        assertThat(refused.get("dues").get("currency").asString()).isEqualTo("INR");

        UUID card = card(rider, "tok_ok");
        JsonNode paying = assertAnswered("POST", PAY, payDues(rider, "{\"payment_method_id\": \"" + card + "\"}"),
                202);

        assertThat(paying.get("total").get("amount_paise").asLong()).isEqualTo(declined.amountPaise());
        assertThat(paying.get("charges")).singleElement().satisfies(charge ->
                assertThat(charge.get("status").asString()).isEqualTo("PENDING"));
        assertThat(attemptsOf(declined)).extracting(attempt -> attempt.seq() + " " + attempt.status())
                .containsExactly("1 FAILED", "2 PENDING");
        assertThat(attempt(declined).methodRef()).isEqualTo("tok_ok");
        assertThat(reread(declined).paymentMethodId()).isEqualTo(card);
        JsonNode none = assertAnswered("GET", DUES, getAs(rider.authorization(), DUES), 200);
        assertThat(none.get("total").get("amount_paise").asLong()).isZero();
        assertThat(none.get("total").get("currency").asString()).isEqualTo("INR");
        assertThat(none.get("charges")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_log WHERE entity_id = :id AND action = 'dues.pay'")
                .param("id", rider.id().toString()).query(Long.class).single()).isEqualTo(1);

        send();

        assertThat(reread(declined).status()).isEqualTo("SUCCEEDED");
        assertAnswered("POST", "/v1/rides", book(rider, quote), 201);
        assertThat(violations()).isEmpty();
    }

    @Test
    void duesArePaidWithTheRidersOwnCardOrUpiMethod() {
        TestUser rider = rides.rider("Rider");
        TestUser other = riderWith("tok_ok");
        assertProblem("POST", PAY, payDues(rider, "{}"), 409, "NO_DUES");
        pendingDues(rider);
        UUID removed = card(rider, "tok_ok");
        profiles.removePaymentMethod(rider.id(), removed);
        UUID cash = jdbc.sql("SELECT id FROM rider.payment_methods WHERE rider_id = :id AND type = 'CASH'")
                .param("id", rider.id()).query(UUID.class).single();
        UUID othersCard = card(other, "tok_ok");

        for (String body : List.of("{}", "{\"payment_method_id\": \"" + cash + "\"}",
                "{\"payment_method_id\": \"" + removed + "\"}", "{\"payment_method_id\": \"" + othersCard + "\"}",
                "{\"payment_method_id\": \"" + UUID.randomUUID() + "\"}")) {
            assertProblem("POST", PAY, payDues(rider, body), 422, "PAYMENT_METHOD_INVALID");
        }
        assertProblem("POST", PAY, payDues(rider, "{\"payment_method_id\": \"x\"}"), 400, "MALFORMED_REQUEST");
        assertThat(attemptsOf(dues(rider).getFirst())).hasSize(1);

        profiles.setDefaultPaymentMethod(rider.id(), profiles.addPaymentMethod(rider.id(), "UPI", "tok_ok",
                "rider@upi").id());
        assertAnswered("POST", PAY, payDues(rider, "{}"), 202);
        ChargeRow paying = chargesOf(rider).getFirst();
        assertThat(paying.status()).isEqualTo("PENDING");
        assertThat(paying.methodType()).as("the UPI default").isEqualTo("UPI");
        assertThat(attempt(paying).methodRef()).isEqualTo("tok_ok");
        JsonNode others = assertAnswered("GET", DUES, getAs(other.authorization(), DUES), 200);
        assertThat(others.get("total").get("amount_paise").asLong()).as("dues are the rider's own").isZero();
    }

    @Test
    void aRepeatedPaymentFindsNoDuesAndTheSameKeyReplays() {
        TestUser rider = riderWith("tok_ok");
        pendingDues(rider);
        String key = UUID.randomUUID().toString();

        HttpResponse<String> first = payDues(rider, "{}", key);
        HttpResponse<String> replayed = payDues(rider, "{}", key);
        HttpResponse<String> again = payDues(rider, "{}");

        assertAnswered("POST", PAY, first, 202);
        assertAnswered("POST", PAY, replayed, 202);
        assertThat(replayed.body()).isEqualTo(first.body());
        assertThat(replayed.headers().firstValue(Idempotency.REPLAYED_HEADER)).contains("true");
        assertProblem("POST", PAY, again, 409, "NO_DUES");
    }

    @Test
    @Tag("race")
    void concurrentPaymentsStartOneAttemptPerCharge() throws InterruptedException {
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            TestUser rider = riderWith("tok_ok");
            pendingDues(rider);
            pendingDues(rider);

            List<String> outcomes = RaceRunner.race(() -> status(payDues(rider, "{}")),
                    () -> status(payDues(rider, "{}")));

            assertThat(outcomes).containsExactlyInAnyOrder("202", "409 NO_DUES");
            assertThat(chargesOf(rider)).allSatisfy(charge -> {
                assertThat(charge.status()).isEqualTo("PENDING");
                assertThat(attemptsOf(charge)).hasSize(2);
            });
        }
        assertThat(violations()).isEmpty();
    }

    /** A declined fare of 10,000 paise for a ride of the rider's that exists only here. */
    private ChargeRow pendingDues(TestUser rider) {
        ChargeRow charge = pendingCharge(rider.id(), null, "FARE", "tok_decline");
        send();
        assertThat(reread(charge).status()).isEqualTo("FAILED");
        return charge;
    }

    private List<ChargeRow> dues(TestUser rider) {
        return charges.failed(rider.id());
    }

    private List<ChargeRow> chargesOf(TestUser rider) {
        return jdbc.sql("SELECT id FROM payment.charges WHERE rider_id = :id").param("id", rider.id())
                .query(UUID.class).list().stream().map(id -> charges.find(id).orElseThrow()).toList();
    }

    private UUID quote(TestUser rider) {
        GeoPoint pickup = city.at(0.1, 0.1);
        return rides.quote(rider.id(), pickup, new GeoPoint(pickup.lat() + 0.05, pickup.lon()), "MINI");
    }

    private HttpResponse<String> book(TestUser rider, UUID quote) {
        return postJson("/v1/rides", Map.of("Authorization", rider.authorization(), Idempotency.HEADER,
                UUID.randomUUID().toString()), "{\"quote_id\": \"" + quote + "\"}");
    }

    private static String status(HttpResponse<String> response) {
        return response.statusCode() == 202 ? "202"
                : response.statusCode() + " " + json(response).path("code").asString();
    }
}

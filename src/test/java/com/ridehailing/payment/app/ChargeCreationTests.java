package com.ridehailing.payment.app;

import static com.ridehailing.support.EventContract.outboxEvents;
import static com.ridehailing.support.TestRides.asApi;
import static com.ridehailing.support.TestRides.asWorker;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.payment.db.AttemptRepository.AttemptRow;
import com.ridehailing.payment.db.ChargeRepository.ChargeRow;
import com.ridehailing.support.EventContract;
import com.ridehailing.support.TestRides.AssignedRide;
import com.ridehailing.support.TestUsers.TestUser;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Charges from ride events (LLD §11.1, §11.10): cash, online, the method to charge, fees, and redelivery. */
class ChargeCreationTests extends PaymentTest {

    @Test
    void aCashFareIsPaidAtOnceAndCollectedByTheDriver() {
        TestUser rider = rides.rider("Cash");

        AssignedRide ride = completed(rider);

        ChargeRow fare = fare(ride);
        assertThat(fare.status()).isEqualTo("SUCCEEDED");
        assertThat(fare.methodType()).isEqualTo("CASH");
        assertThat(fare.amountPaise()).isEqualTo(farePaise(ride));
        assertThat(attemptsOf(fare)).isEmpty();
        assertThat(outboxEvents(jdbc, "ChargeSucceeded", fare.id())).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("correlation_id").asString()).isEqualTo(ride.id().toString());
            assertThat(event.get("payload").has("attempt_id")).isFalse();
            assertThat(event.get("payload").get("method_type").asString()).isEqualTo("CASH");
        });
        assertThat(jdbc.sql("SELECT partition_key FROM platform.outbox WHERE aggregate_id = :id")
                .param("id", fare.id()).query(UUID.class).single()).as("ordered with the ride's events")
                .isEqualTo(ride.id());
        assertThat(earnings(ride.id())).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "FARE").containsEntry("gross_paise", farePaise(ride))
                    .containsEntry("cash_collected_paise", farePaise(ride));
            assertThat((long) row.get("net_paise")).isEqualTo(farePaise(ride) - (long) row.get("commission_paise"));
        });
        assertThat(violations()).isEmpty();
    }

    @Test
    void anOnlineFareWaitsForTheExecutorThenSucceeds() {
        TestUser rider = riderWith("tok_ok");

        AssignedRide ride = completed(rider);

        ChargeRow pending = fare(ride);
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.methodType()).isEqualTo("CARD");
        assertThat(attemptsOf(pending)).singleElement().satisfies(attempt -> {
            assertThat(attempt.seq()).isEqualTo(1);
            assertThat(attempt.status()).isEqualTo("PENDING");
            assertThat(attempt.methodRef()).isEqualTo("tok_ok");
            assertThat(attempt.provider()).isEqualTo("mock");
        });
        assertThat(outboxEvents(jdbc, "ChargeSucceeded", pending.id())).isEmpty();
        assertThat(earnings(ride.id())).singleElement().satisfies(row ->
                assertThat(row).containsEntry("kind", "FARE").containsEntry("cash_collected_paise", 0L));

        assertThat(send()).isEqualTo(1);

        ChargeRow paid = reread(pending);
        AttemptRow attempt = attempt(paid);
        assertThat(paid.status()).isEqualTo("SUCCEEDED");
        assertThat(paid.succeededAttemptId()).isEqualTo(attempt.id());
        assertThat(attempt.status()).isEqualTo("SUCCEEDED");
        assertThat(attempt.providerPaymentId()).isEqualTo("pay_" + attempt.id());
        assertThat(mock.calls(attempt.id())).isEqualTo(1);
        assertThat(outboxEvents(jdbc, "ChargeSucceeded", paid.id())).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("attempt_id").asString()).isEqualTo(attempt.id().toString());
            assertThat(event.get("aggregate_version").asInt()).isEqualTo(paid.version());
        });
        assertThat(earnings(ride.id())).as("the fare's row, written once").hasSize(1);
        assertThat(violations()).isEmpty();
    }

    @Test
    void theBookedCardIsChargedThoughAnotherIsNowTheDefault() {
        TestUser rider = riderWith("tok_ok");
        AssignedRide ride = assigned(rider);
        UUID booked = bookedMethod(ride);
        profiles.setDefaultPaymentMethod(rider.id(), card(rider, "tok_decline"));

        finish(ride);

        assertThat(fare(ride).paymentMethodId()).isEqualTo(booked);
    }

    @Test
    void aNoShowChargesTheNoShowFee() {
        TestUser rider = riderWith("tok_ok");
        AssignedRide ride = assigned(rider);
        asApi(() -> drivers.arrive(ride.driver().id(), ride.id()));
        jdbc.sql("UPDATE ride.rides SET arrived_at = arrived_at - interval '301 seconds' WHERE id = :id")
                .param("id", ride.id()).update();
        asApi(() -> drivers.noShow(ride.driver().id(), ride.id()));

        deliver(ride);

        ChargeRow fee = charge(ride.id(), "NO_SHOW_FEE");
        assertThat(fee.amountPaise()).isEqualTo(7_500);
        assertThat(fee.commissionPaise()).isEqualTo(1_500);
        assertThat(fee.driverId()).isEqualTo(ride.driver().id());
        assertThat(fee.status()).isEqualTo("PENDING");
    }

    @Test
    void aFeeWithoutADriverIsChargedButEarnsNoOne() {
        ChargeRow fee = pendingCharge(UUID.randomUUID(), null, "CANCELLATION_FEE", "tok_ok");

        send();

        assertThat(reread(fee).status()).isEqualTo("SUCCEEDED");
        assertThat(earnings(fee.rideId())).isEmpty();
    }

    @Test
    void aRedeliveredOrReplayedEventFindsTheChargeAndStops() {
        AssignedRide ride = completed(riderWith("tok_ok"));
        UUID tripCompleted = eventId("TripCompleted", ride.id());

        assertThat(asWorker(() -> events.deliver(ChargeConsumer.NAME, tripCompleted))).isFalse();
        asWorker(() -> {
            events.replay(ChargeConsumer.NAME, tripCompleted);
            events.replay(EarningsConsumer.NAME, tripCompleted);
            return null;
        });

        assertThat(chargeCount(ride.id())).isEqualTo(1);
        assertThat(attemptsOf(fare(ride))).hasSize(1);
        assertThat(earnings(ride.id())).hasSize(1);
        send();
        assertThat(mock.calls(attempt(fare(ride)).id())).isEqualTo(1);
    }

    @Test
    void aRemovedMethodFallsBackToTheDefaultCard() {
        TestUser rider = riderWith("tok_ok");
        AssignedRide ride = assigned(rider);
        UUID other = card(rider, "tok_decline");
        profiles.setDefaultPaymentMethod(rider.id(), other);
        profiles.removePaymentMethod(rider.id(), bookedMethod(ride));

        finish(ride);

        assertThat(fare(ride).paymentMethodId()).isEqualTo(other);
        assertThat(attempt(fare(ride)).methodRef()).isEqualTo("tok_decline");
    }

    @Test
    void withACashDefaultTheNewestCardIsCharged() {
        TestUser rider = riderWith("tok_ok");
        AssignedRide ride = assigned(rider);
        UUID older = card(rider, "tok_decline");
        UUID newest = card(rider, "tok_timeout_succeeded");
        profiles.removePaymentMethod(rider.id(), bookedMethod(ride));

        finish(ride);

        assertThat(fare(ride).paymentMethodId()).isNotEqualTo(older).isEqualTo(newest);
    }

    @Test
    void withoutAnOnlineMethodTheFareBecomesDues() {
        TestUser rider = riderWith("tok_ok");
        AssignedRide ride = assigned(rider);
        profiles.removePaymentMethod(rider.id(), bookedMethod(ride));

        finish(ride);

        ChargeRow charge = fare(ride);
        assertThat(charge.status()).isEqualTo("FAILED");
        assertThat(charge.failureCode()).isEqualTo("NO_PAYMENT_METHOD");
        assertThat(charge.methodType()).as("the ride's").isEqualTo("CARD");
        assertThat(charge.paymentMethodId()).isNull();
        assertThat(attemptsOf(charge)).isEmpty();
        assertThat(outboxEvents(jdbc, "ChargeFailed", charge.id())).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("failure_code").asString()).isEqualTo("NO_PAYMENT_METHOD");
            assertThat(event.get("payload").has("attempt_id")).isFalse();
        });
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_log WHERE entity_id = :id AND action = 'charge.failed'")
                .param("id", charge.id().toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aFeeOnACashRideGoesToTheRidersCardOrBecomesDues() {
        TestUser withCard = rides.rider("Cash");
        UUID card = card(withCard, "tok_ok");
        TestUser cashOnly = rides.rider("Cash");

        ChargeRow fee = charge(cancelledWithFee(withCard).id(), "CANCELLATION_FEE");
        ChargeRow unpaid = charge(cancelledWithFee(cashOnly).id(), "CANCELLATION_FEE");

        assertThat(fee.status()).isEqualTo("PENDING");
        assertThat(fee.paymentMethodId()).isEqualTo(card);
        assertThat(fee.amountPaise()).isEqualTo(5_000);
        assertThat(fee.commissionPaise()).isEqualTo(1_000);
        assertThat(unpaid.status()).isEqualTo("FAILED");
        assertThat(unpaid.failureCode()).isEqualTo("NO_PAYMENT_METHOD");
        assertThat(unpaid.methodType()).isEqualTo("CASH");
    }

    @Test
    void aPaidFeeEarnsTheDriverItsNetOnTheDayItWasPaid() {
        AssignedRide ride = cancelledWithFee(riderWith("tok_ok"));
        ChargeRow fee = charge(ride.id(), "CANCELLATION_FEE");
        assertThat(earnings(ride.id())).isEmpty();

        send();

        ChargeRow paid = reread(fee);
        assertThat(earnings(ride.id())).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "CANCELLATION_FEE").containsEntry("gross_paise", 5_000L)
                    .containsEntry("commission_paise", 1_000L).containsEntry("net_paise", 4_000L)
                    .containsEntry("cash_collected_paise", 0L)
                    .containsEntry("driver_id", ride.driver().id()).containsEntry("source_id", paid.id());
            assertThat(row.get("earned_on")).isEqualTo(
                    LocalDate.ofInstant(paid.updatedAt(), ZoneId.of("Asia/Kolkata")));
        });
    }

    @Test
    void aFreeCancellationChargesNothing() {
        TestUser rider = riderWith("tok_ok");
        AssignedRide ride = assigned(rider);
        postJson("/v1/rides/" + ride.id() + "/cancel", Map.of("Authorization", rider.authorization(),
                "Idempotency-Key", UUID.randomUUID().toString()), "{}");

        deliver(ride);

        assertThat(chargeCount(ride.id())).isZero();
    }

    private UUID bookedMethod(AssignedRide ride) {
        return jdbc.sql("SELECT payment_method_id FROM ride.rides WHERE id = :id").param("id", ride.id())
                .query(UUID.class).single();
    }

    private long farePaise(AssignedRide ride) {
        return jdbc.sql("SELECT fare_paise FROM ride.rides WHERE id = :id").param("id", ride.id())
                .query(Long.class).single();
    }

    private long chargeCount(UUID rideId) {
        return jdbc.sql("SELECT count(*) FROM payment.charges WHERE ride_id = :id").param("id", rideId)
                .query(Long.class).single();
    }

    private UUID eventId(String type, UUID aggregateId) {
        List<JsonNode> found = outboxEvents(jdbc, type, aggregateId);
        return UUID.fromString(found.getFirst().get("event_id").asString());
    }

    private List<Map<String, Object>> earnings(UUID rideId) {
        return jdbc.sql("""
                        SELECT kind, driver_id, source_id, gross_paise, commission_paise, net_paise,
                               cash_collected_paise, earned_on
                        FROM payment.driver_earnings WHERE ride_id = :id ORDER BY earned_at
                        """)
                .param("id", rideId)
                .query((row, n) -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("kind", row.getString("kind"));
                    values.put("driver_id", row.getObject("driver_id", UUID.class));
                    values.put("source_id", row.getObject("source_id", UUID.class));
                    values.put("gross_paise", row.getLong("gross_paise"));
                    values.put("commission_paise", row.getLong("commission_paise"));
                    values.put("net_paise", row.getLong("net_paise"));
                    values.put("cash_collected_paise", row.getLong("cash_collected_paise"));
                    values.put("earned_on", row.getObject("earned_on", LocalDate.class));
                    return values;
                })
                .list();
    }
}

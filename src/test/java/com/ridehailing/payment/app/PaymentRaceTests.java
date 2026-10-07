package com.ridehailing.payment.app;

import static com.ridehailing.support.EventContract.outboxEvents;
import static com.ridehailing.support.TestRides.asApi;
import static com.ridehailing.support.TestRides.asWorker;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.payment.db.AttemptRepository.AttemptRow;
import com.ridehailing.payment.db.ChargeRepository.ChargeRow;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.support.RaceRunner;
import com.ridehailing.support.TestRides.AssignedRide;
import com.ridehailing.support.TestUsers.TestUser;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Races on charges (LLD §17.2, ride lifecycle §8 scenario 6): a retried completion and redelivered events never
 * charge twice, and a webhook racing a status check applies the outcome once.
 */
@Tag("race")
class PaymentRaceTests extends PaymentTest {

    @Test
    void race6_aCompletionRetriedAfterTheChargeSucceededChangesNothing() throws InterruptedException {
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            newCity();
            AssignedRide ride = startedRide();
            String key = UUID.randomUUID().toString();
            JsonNode completed = json(complete(ride, key));
            deliver(ride);
            send();
            UUID tripCompleted = tripCompleted(ride);

            List<String> outcomes = RaceRunner.race(List.of(
                    () -> versionAfter(complete(ride, key)),
                    () -> versionAfter(complete(ride, UUID.randomUUID().toString())),
                    () -> String.valueOf(asWorker(() -> events.deliver(ChargeConsumer.NAME, tripCompleted))),
                    () -> asWorker(() -> {
                        events.replay(ChargeConsumer.NAME, tripCompleted);
                        return "replayed";
                    })));

            String version = completed.get("version").asString();
            assertThat(outcomes).containsExactly("200 " + version, "200 " + version, "false", "replayed");
            assertChargedOnce(ride);
            assertThat(violations()).isEmpty();
        }
    }

    @Test
    void twoDeliveriesOfOneTripCompletedAtOnceCreateOneCharge() throws InterruptedException {
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            newCity();
            AssignedRide ride = startedRide();
            assertThat(complete(ride, UUID.randomUUID().toString()).statusCode()).isEqualTo(200);
            UUID tripCompleted = tripCompleted(ride);

            List<String> outcomes = RaceRunner.staggered(
                    () -> String.valueOf(asWorker(() -> events.deliver(ChargeConsumer.NAME, tripCompleted))),
                    () -> asWorker(() -> {
                        events.replay(ChargeConsumer.NAME, tripCompleted);
                        return "replayed";
                    }));

            assertThat(outcomes).containsExactly("true", "replayed");
            assertThat(attemptsOf(fare(ride))).hasSize(1);
            send();
            assertChargedOnce(ride);
            assertThat(violations()).isEmpty();
        }
    }

    @Test
    void aWebhookRacingAStatusCheckAppliesTheOutcomeOnce() throws InterruptedException {
        Map<String, Integer> seen = new TreeMap<>();
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            // Each repetition times out once; a circuit per repetition keeps five of them from opening it.
            executor = executorWith(mock, properties);
            ChargeRow charge = pendingCharge(UUID.randomUUID(), null, "FARE", "tok_timeout_succeeded");
            send();
            AttemptRow attempt = attempt(charge);
            dueNow("payment.charge_attempts", attempt.id());
            String body = mock.webhookBody(attempt.id()).orElseThrow();

            List<String> outcomes = RaceRunner.staggered(
                    () -> asWorker(() -> String.valueOf(executor.checkNext())),
                    () -> String.valueOf(postWebhook(body).statusCode()));

            assertThat(outcomes.get(1)).isEqualTo("200");
            assertThat(outcomes.get(0)).as("false when the webhook resolved it before the claim").isIn("true", "false");
            assertThat(reread(charge).status()).isEqualTo("SUCCEEDED");
            assertThat(outboxEvents(jdbc, "ChargeSucceeded", charge.id())).hasSize(1);
            assertThat(mock.calls(attempt.id())).isEqualTo(1);
            String webhook = jdbc.sql("SELECT outcome FROM payment.provider_webhooks WHERE raw_body = :body")
                    .param("body", body).query(String.class).single();
            seen.merge(webhook, 1, Integer::sum);
        }
        RaceRunner.assertExplored(seen, "APPLIED", "IGNORED");
        assertThat(violations()).isEmpty();
    }

    private AssignedRide startedRide() {
        TestUser rider = riderWith("tok_ok");
        AssignedRide ride = assigned(rider);
        asApi(() -> {
            drivers.arrive(ride.driver().id(), ride.id());
            return drivers.start(ride.driver().id(), ride.id(), ride.pin());
        });
        return ride;
    }

    private void assertChargedOnce(AssignedRide ride) {
        ChargeRow charge = fare(ride);
        assertThat(jdbc.sql("SELECT count(*) FROM payment.charges WHERE ride_id = :id").param("id", ride.id())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(charge.status()).isEqualTo("SUCCEEDED");
        assertThat(attemptsOf(charge)).singleElement().satisfies(attempt ->
                assertThat(mock.calls(attempt.id())).isEqualTo(1));
        assertThat(outboxEvents(jdbc, "ChargeSucceeded", charge.id())).hasSize(1);
    }

    private HttpResponse<String> complete(AssignedRide ride, String key) {
        return postJson("/v1/rides/" + ride.id() + "/complete", Map.of("Authorization",
                ride.driver().authorization(), Idempotency.HEADER, key), "{}");
    }

    private static String versionAfter(HttpResponse<String> response) {
        return response.statusCode() + " " + json(response).path("version").asString();
    }

    private UUID tripCompleted(AssignedRide ride) {
        return UUID.fromString(outboxEvents(jdbc, "TripCompleted", ride.id()).getFirst().get("event_id").asString());
    }
}

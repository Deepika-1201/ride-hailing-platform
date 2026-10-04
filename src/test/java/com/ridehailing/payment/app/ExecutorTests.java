package com.ridehailing.payment.app;

import static com.ridehailing.support.EventContract.outboxEvents;
import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.TestRides.asWorker;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.payment.db.AttemptRepository.AttemptRow;
import com.ridehailing.payment.db.ChargeRepository.ChargeRow;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.EventContract;
import com.ridehailing.support.TestRides.AssignedRide;
import com.ridehailing.support.TestUsers;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The executor and status checks (LLD §11.2, §11.3, §11.10): each mock token's path, the check schedule, the 24-hour
 * limit, a crash during a call, and the circuit breaker. No test sends an attempt twice.
 */
class ExecutorTests extends PaymentTest {

    private static final String ATTEMPTS = "payment.charge_attempts";

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private TestUsers users;

    @Test
    void aDeclineBecomesDues() {
        double failed = outcomes("FAILED");
        AssignedRide ride = completed(riderWith("tok_decline"));

        send();

        ChargeRow charge = fare(ride);
        AttemptRow attempt = attempt(charge);
        assertThat(charge.status()).isEqualTo("FAILED");
        assertThat(charge.failureCode()).isEqualTo("DECLINED");
        assertThat(attempt.status()).isEqualTo("FAILED");
        assertThat(attempt.failureCode()).isEqualTo("DECLINED");
        assertThat(outboxEvents(jdbc, "ChargeFailed", charge.id())).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("attempt_id").asString()).isEqualTo(attempt.id().toString());
        });
        assertThat(outcomes("FAILED")).isEqualTo(failed + 1);
        assertThat(mock.calls(attempt.id())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_log WHERE entity_id = :id AND action = 'charge.failed'")
                .param("id", charge.id().toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(violations()).isEmpty();
    }

    @Test
    void aTimeoutIsUnknownUntilAStatusCheckFindsTheDecline() {
        double unknown = outcomes("UNKNOWN");
        AssignedRide ride = completed(riderWith("tok_timeout_failed"));

        send();

        ChargeRow charge = fare(ride);
        AttemptRow attempt = attempt(charge);
        assertThat(charge.status()).isEqualTo("UNKNOWN");
        assertThat(attempt.status()).isEqualTo("UNKNOWN");
        assertThat(secondsUntilCheck(attempt.id())).isBetween(5.0, 10.0);
        assertThat(outcomes("UNKNOWN")).isEqualTo(unknown + 1);
        assertThat(check()).as("the first check is 10 s away").isZero();

        dueNow(ATTEMPTS, attempt.id());
        assertThat(check()).isEqualTo(1);

        assertThat(reread(charge).status()).isEqualTo("FAILED");
        assertThat(attempt(charge).status()).isEqualTo("FAILED");
        assertThat(attempt(charge).checks()).isEqualTo(1);
        assertThat(outboxEvents(jdbc, "ChargeFailed", charge.id())).hasSize(1);
        assertThat(mock.calls(attempt.id())).as("checked, never sent again").isEqualTo(1);
    }

    @Test
    void aTimeoutThenSuccessIsFoundByTheStatusCheck() {
        double succeeded = outcomes("SUCCEEDED");
        AssignedRide ride = completed(riderWith("tok_timeout_succeeded"));
        send();
        AttemptRow attempt = attempt(fare(ride));
        assertThat(attempt.status()).isEqualTo("UNKNOWN");

        dueNow(ATTEMPTS, attempt.id());
        check();

        ChargeRow charge = fare(ride);
        assertThat(charge.status()).isEqualTo("SUCCEEDED");
        assertThat(attempt(charge).providerPaymentId()).isEqualTo("pay_" + attempt.id());
        assertThat(outboxEvents(jdbc, "ChargeSucceeded", charge.id())).hasSize(1);
        assertThat(mock.calls(attempt.id())).isEqualTo(1);
        assertThat(outcomes("SUCCEEDED")).isEqualTo(succeeded + 1);
        assertThat(violations()).isEmpty();
    }

    @Test
    void aCheckThatDiesIsDueAgainOnceItsLeasePasses() {
        AssignedRide ride = completed(riderWith("tok_timeout_succeeded"));
        send();
        AttemptRow attempt = attempt(fare(ride));
        dueNow(ATTEMPTS, attempt.id());
        ScriptedProvider crashing = new ScriptedProvider(mock);
        crashing.crashAfterCall = true;

        assertThatThrownBy(() -> asWorker(executorWith(crashing, properties)::checkNext))
                .isInstanceOf(ScriptedProvider.SimulatedCrash.class);

        assertThat(attempt(fare(ride)).status()).isEqualTo("UNKNOWN");
        assertThat(secondsUntilCheck(attempt.id())).as("the check's lease").isBetween(25.0, 30.0);
        assertThat(check()).isZero();
        dueNow(ATTEMPTS, attempt.id());
        assertThat(check()).isEqualTo(1);
        assertThat(fare(ride).status()).isEqualTo("SUCCEEDED");
        assertThat(attempt(fare(ride)).checks()).isEqualTo(2);
    }

    @Test
    void aWebhookBeforeATimedOutAnswerIsNotUndoneByIt() {
        double unknown = outcomes("UNKNOWN");
        AssignedRide ride = completed(riderWith("tok_timeout_succeeded"));
        ScriptedProvider scripted = new ScriptedProvider(mock);
        scripted.duringCall = key -> assertThat(postWebhook(mock.webhookBody(key).orElseThrow()).statusCode())
                .isEqualTo(200);

        assertThat(asWorker(executorWith(scripted, properties)::sendNext)).isTrue();

        assertThat(fare(ride).status()).isEqualTo("SUCCEEDED");
        assertThat(attempt(fare(ride)).status()).isEqualTo("SUCCEEDED");
        assertThat(attempt(fare(ride)).nextCheckAt()).isNull();
        assertThat(outcomes("UNKNOWN")).as("the timeout came after the outcome").isEqualTo(unknown);
    }

    @Test
    void anUnansweredPaymentIsCheckedOnTheScheduleUntilItsWebhookArrives() {
        AssignedRide ride = completed(riderWith("tok_webhook_only"));
        send();
        AttemptRow attempt = attempt(fare(ride));
        assertThat(attempt.status()).as("the provider answered PENDING").isEqualTo("UNKNOWN");
        assertThat(fare(ride).status()).isEqualTo("UNKNOWN");

        for (double expected : List.of(30.0, 120.0, 600.0, 3_600.0, 3_600.0)) {
            dueNow(ATTEMPTS, attempt.id());
            assertThat(check()).isEqualTo(1);
            assertThat(attempt(fare(ride)).status()).isEqualTo("UNKNOWN");
            assertThat(secondsUntilCheck(attempt.id())).isBetween(expected - 5, expected);
        }

        String body = mock.webhookBody(attempt.id()).orElseThrow();
        assertThat(postWebhook(body).statusCode()).isEqualTo(200);

        ChargeRow charge = fare(ride);
        assertThat(charge.status()).isEqualTo("SUCCEEDED");
        assertThat(attempt(charge).status()).isEqualTo("SUCCEEDED");
        assertThat(attempt(charge).checks()).isEqualTo(5);
        assertThat(jdbc.sql("SELECT outcome FROM payment.provider_webhooks WHERE raw_body = :body")
                .param("body", body).query(String.class).single()).isEqualTo("APPLIED");
        assertThat(mock.calls(attempt.id())).isEqualTo(1);
    }

    @Test
    void checksStopAfter24HoursAndTheChargeWaitsForOperations() {
        double unresolved = outcomes("UNRESOLVED");
        AssignedRide ride = completed(riderWith("tok_webhook_only"));
        send();
        AttemptRow attempt = attempt(fare(ride));
        sentAgo(ATTEMPTS, attempt.id(), Duration.ofHours(23).plusMinutes(59));
        dueNow(ATTEMPTS, attempt.id());
        check();
        assertThat(secondsUntilCheck(attempt.id())).as("still within 24 h").isPositive();

        sentAgo(ATTEMPTS, attempt.id(), Duration.ofHours(24));
        dueNow(ATTEMPTS, attempt.id());
        assertThat(check()).isEqualTo(1);

        assertThat(attempt(fare(ride)).status()).isEqualTo("UNKNOWN");
        assertThat(attempt(fare(ride)).nextCheckAt()).isNull();
        assertThat(check()).isZero();
        assertThat(outcomes("UNRESOLVED")).isEqualTo(unresolved + 1);
        jdbc.sql("UPDATE payment.charges SET updated_at = now() - interval '25 hours' WHERE id = :id")
                .param("id", fare(ride).id()).update();
        String ops = users.create(UserRole.OPS).authorization();
        JsonNode listed = assertAnswered("GET", "/v1/ops/payments",
                getAs(ops, "/v1/ops/payments?status=UNKNOWN&older_than=PT24H&limit=100"), 200);
        assertThat(listed.get("items").valueStream().map(item -> item.get("id").asString()))
                .contains(fare(ride).id().toString());
        JsonNode recent = assertAnswered("GET", "/v1/ops/payments",
                getAs(ops, "/v1/ops/payments?status=UNKNOWN&older_than=PT26H&limit=100"), 200);
        assertThat(recent.get("items").valueStream().map(item -> item.get("id").asString()))
                .doesNotContain(fare(ride).id().toString());
    }

    @Test
    void aCallThatNeverReachedTheProviderIsNotReceivedAfterTwoMinutes() {
        AssignedRide ride = completed(riderWith("tok_ok"));
        ScriptedProvider scripted = new ScriptedProvider(mock);
        scripted.crashBeforeCall = true;
        assertThatThrownBy(() -> asWorker(executorWith(scripted, properties)::sendNext))
                .isInstanceOf(ScriptedProvider.SimulatedCrash.class);
        AttemptRow attempt = attempt(fare(ride));
        assertThat(attempt.status()).isEqualTo("IN_FLIGHT");
        assertThat(send()).as("an attempt in flight is never sent again").isZero();

        expireLease(attempt.id());
        assertThat(check()).isEqualTo(1);

        assertThat(attempt(fare(ride)).status()).as("not found yet, but only just sent").isEqualTo("UNKNOWN");
        assertThat(fare(ride).status()).isEqualTo("UNKNOWN");
        assertThat(secondsUntilCheck(attempt.id())).isBetween(25.0, 30.0);

        sentAgo(ATTEMPTS, attempt.id(), Duration.ofMinutes(2).plusSeconds(1));
        dueNow(ATTEMPTS, attempt.id());
        check();

        ChargeRow charge = fare(ride);
        assertThat(attempt(charge).status()).isEqualTo("FAILED");
        assertThat(attempt(charge).failureCode()).isEqualTo("NOT_RECEIVED");
        assertThat(charge.status()).as("dues the rider can pay again").isEqualTo("FAILED");
        assertThat(charge.failureCode()).isEqualTo("NOT_RECEIVED");
        assertThat(mock.calls(attempt.id())).isZero();
    }

    @Test
    void aCrashDuringTheCallIsResolvedByAStatusCheckNotASecondSend() {
        AssignedRide ride = completed(riderWith("tok_ok"));
        ScriptedProvider scripted = new ScriptedProvider(mock);
        scripted.crashAfterCall = true;
        assertThatThrownBy(() -> asWorker(executorWith(scripted, properties)::sendNext))
                .isInstanceOf(ScriptedProvider.SimulatedCrash.class);
        AttemptRow attempt = attempt(fare(ride));
        assertThat(attempt.status()).isEqualTo("IN_FLIGHT");
        assertThat(mock.calls(attempt.id())).isEqualTo(1);

        assertThat(send()).isZero();
        assertThat(check()).as("the lease hasn't expired").isZero();
        expireLease(attempt.id());
        assertThat(check()).isEqualTo(1);

        ChargeRow charge = fare(ride);
        assertThat(charge.status()).isEqualTo("SUCCEEDED");
        assertThat(attempt(charge).checks()).isEqualTo(1);
        assertThat(outboxEvents(jdbc, "ChargeSucceeded", charge.id())).hasSize(1);
        assertThat(mock.calls(attempt.id())).isEqualTo(1);
        assertThat(violations()).isEmpty();
    }

    @Test
    void anOpenCircuitLeavesAttemptsPendingUntilTheProviderAnswersAgain() throws InterruptedException {
        PaymentProperties twoFailures = new PaymentProperties(properties.workers(), properties.pollInterval(),
                properties.lease(), properties.readTimeout(), properties.checkSchedule(), properties.checkFor(),
                properties.notReceivedAfter(), properties.webhookTolerance(), 2, Duration.ofMillis(200),
                properties.currency(), properties.mock());
        ScriptedProvider scripted = new ScriptedProvider(mock);
        PaymentExecutor guarded = executorWith(scripted, twoFailures);
        List<AssignedRide> trips = List.of(completed(riderWith("tok_ok")), completed(riderWith("tok_ok")),
                completed(riderWith("tok_ok")), completed(riderWith("tok_ok")));
        scripted.down = true;

        assertThat(asWorker(guarded::sendNext)).isTrue();
        assertThat(asWorker(guarded::sendNext)).isTrue();
        assertThat(asWorker(guarded::sendNext)).as("open after two calls without an answer").isFalse();
        AttemptRow due = attempt(fare(trips.get(0)));
        dueNow(ATTEMPTS, due.id());
        assertThat(asWorker(guarded::checkNext)).as("a due check waits too").isFalse();
        assertThat(attempt(fare(trips.get(0))).checks()).isZero();

        assertThat(trips.stream().map(trip -> attempt(fare(trip)).status()))
                .containsExactly("UNKNOWN", "UNKNOWN", "PENDING", "PENDING");
        Thread.sleep(250);
        assertThat(asWorker(guarded::sendNext)).as("one more failure after reopening").isTrue();
        assertThat(asWorker(guarded::sendNext)).as("reopened at once").isFalse();

        scripted.down = false;
        Thread.sleep(250);
        assertThat(asWorker(guarded::sendNext)).isTrue();
        assertThat(attempt(fare(trips.get(3))).status()).isEqualTo("SUCCEEDED");
        assertThat(trips.stream().map(trip -> mock.calls(attempt(fare(trip)).id())))
                .as("calls without an answer never reached the mock").containsExactly(0, 0, 0, 1);

        AssignedRide after = completed(riderWith("tok_ok"));
        completed(riderWith("tok_ok"));
        scripted.down = true;
        assertThat(asWorker(guarded::sendNext)).isTrue();
        scripted.down = false;
        assertThat(asWorker(guarded::sendNext)).as("an answer reset the count: one failure keeps it closed")
                .isTrue();
        assertThat(attempt(fare(after)).status()).isEqualTo("UNKNOWN");
    }

    @Test
    void theOutcomeCountersAreExported() {
        String scrape = get(managementPort, "/actuator/prometheus").body();

        for (String outcome : List.of("SUCCEEDED", "FAILED", "UNKNOWN", "UNRESOLVED")) {
            assertThat(scrape).contains("payment_charges_total{outcome=\"" + outcome + "\"");
        }
    }

    private double outcomes(String outcome) {
        return meters.counter("payment.charges", "outcome", outcome).count();
    }

    private double secondsUntilCheck(UUID attemptId) {
        return jdbc.sql("SELECT extract(epoch FROM next_check_at - now()) FROM payment.charge_attempts WHERE id = :id")
                .param("id", attemptId).query(Double.class).single();
    }

    private void expireLease(UUID attemptId) {
        jdbc.sql("UPDATE payment.charge_attempts SET lease_until = now() - interval '1 second' WHERE id = :id")
                .param("id", attemptId).update();
    }
}

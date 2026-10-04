package com.ridehailing.payment.app;

import static com.ridehailing.support.EventContract.outboxEvents;
import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static com.ridehailing.support.TestRides.asWorker;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.ridehailing.payment.db.ChargeRepository.ChargeRow;
import com.ridehailing.payment.db.RefundRepository.RefundRow;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.EventContract;
import com.ridehailing.support.RaceRunner;
import com.ridehailing.support.TestRides.AssignedRide;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Operations refunds (FR-PY5, LLD §11.6, §11.10): limits, failures, fees, unknown outcomes and races. */
class RefundTests extends PaymentTest {

    private static final String REFUNDS = "/v1/ops/charges/{charge_id}/refunds";

    @Autowired
    private TestUsers users;

    private TestUser ops;

    @BeforeEach
    void setUpOps() {
        ops = users.create(UserRole.OPS);
    }

    @Test
    void operationsRefundPartOfAChargeThenTheRestAndNoMore() {
        ChargeRow charge = paidCharge();

        JsonNode first = assertAnswered("POST", REFUNDS, refund(ops, charge, 3_000), 202);

        assertThat(first.get("status").asString()).isEqualTo("PENDING");
        assertThat(first.get("automatic").asBoolean()).isFalse();
        assertThat(first.get("amount").get("amount_paise").asLong()).isEqualTo(3_000);
        assertThat(reread(charge).refundedPaise()).isEqualTo(3_000);
        assertThat(send()).isEqualTo(1);
        RefundRow sent = refundsOf(charge).getFirst();
        assertThat(sent.status()).isEqualTo("SUCCEEDED");
        assertThat(sent.requestedBy()).isEqualTo("OPS:" + ops.id());
        assertThat(mock.calls(sent.id())).isEqualTo(1);
        assertThat(outboxEvents(jdbc, "RefundSucceeded", sent.id())).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("automatic").asBoolean()).isFalse();
            assertThat(event.get("payload").get("charge_id").asString()).isEqualTo(charge.id().toString());
        });
        assertThat(audits(sent)).containsExactly("refund.requested", "refund.succeeded");
        assertThat(postWebhook(webhook("evt_" + sent.id(), "refund.succeeded", sent.id(), 3_000, null))
                .statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT outcome FROM payment.provider_webhooks WHERE provider_event_id = :id")
                .param("id", "evt_" + sent.id()).query(String.class).single()).isEqualTo("IGNORED");
        assertThat(outboxEvents(jdbc, "RefundSucceeded", sent.id())).hasSize(1);

        assertAnswered("POST", REFUNDS, refund(ops, charge, 7_000), 202);
        JsonNode over = assertProblem("POST", REFUNDS, refund(ops, charge, 1), 422, "REFUND_EXCEEDS_CHARGE");

        assertThat(over.get("detail").asString()).contains("0 paise");
        assertThat(reread(charge).refundedPaise()).isEqualTo(10_000);
        assertThat(violations()).isEmpty();
    }

    @Test
    void aFailedRefundGivesItsReservationBack() {
        ChargeRow charge = paidCharge();
        ScriptedProvider scripted = new ScriptedProvider(mock);
        scripted.refundAnswer = ProviderAnswer.failed("REFUND_DECLINED");
        assertAnswered("POST", REFUNDS, refund(ops, charge, 10_000), 202);

        assertThat(asWorker(executorWith(scripted, properties)::sendNext)).isTrue();

        RefundRow failed = refundsOf(charge).getFirst();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureCode()).isEqualTo("REFUND_DECLINED");
        assertThat(reread(charge).refundedPaise()).isZero();
        assertThat(outboxEvents(jdbc, "RefundSucceeded", failed.id())).isEmpty();
        assertThat(audits(failed)).containsExactly("refund.requested", "refund.failed");
        assertAnswered("POST", REFUNDS, refund(ops, charge, 10_000), 202);
        assertThat(violations()).isEmpty();
    }

    @Test
    void aRefundWithoutAnAnswerIsCheckedThenResolvedByAWebhookOrNotReceived() {
        ChargeRow charge = paidCharge();
        ScriptedProvider down = new ScriptedProvider(mock);
        down.down = true;
        assertAnswered("POST", REFUNDS, refund(ops, charge, 4_000), 202);
        assertAnswered("POST", REFUNDS, refund(ops, charge, 5_000), 202);
        PaymentExecutor unanswered = executorWith(down, properties);
        asWorker(unanswered::sendNext);
        asWorker(unanswered::sendNext);
        List<RefundRow> unknown = refundsOf(charge);
        assertThat(unknown).extracting(RefundRow::status).containsOnly("UNKNOWN");

        RefundRow answered = unknown.get(0);
        assertThat(postWebhook(webhook("evt_" + answered.id(), "refund.succeeded", answered.id(), 4_000, null))
                .statusCode()).isEqualTo(200);
        RefundRow lost = unknown.get(1);
        dueNow("payment.refunds", lost.id());
        assertThat(check()).isEqualTo(1);
        assertThat(refundsOf(charge).get(1).status()).as("not found, but only just sent").isEqualTo("UNKNOWN");
        sentAgo("payment.refunds", lost.id(), Duration.ofMinutes(3));
        dueNow("payment.refunds", lost.id());
        check();

        assertThat(refundsOf(charge)).extracting(RefundRow::status, RefundRow::failureCode)
                .containsExactly(tuple("SUCCEEDED", null), tuple("FAILED", "NOT_RECEIVED"));
        assertThat(reread(charge).refundedPaise()).isEqualTo(4_000);
        assertThat(violations()).isEmpty();
    }

    @Test
    void onlyASucceededOnlineChargeIsRefundable() {
        ChargeRow pending = pendingCharge(UUID.randomUUID(), null, "FARE", "tok_ok");
        AssignedRide cashRide = completed(rides.rider("Cash"));
        TestUser admin = users.create(UserRole.ADMIN);
        TestUser rider = users.create(UserRole.RIDER);

        assertProblem("POST", REFUNDS, refund(ops, pending, 100), 409, "CHARGE_NOT_REFUNDABLE");
        assertProblem("POST", REFUNDS, refund(ops, fare(cashRide), 100), 409, "CHARGE_NOT_REFUNDABLE");
        assertProblem("POST", REFUNDS, postJson("/v1/ops/charges/" + UUID.randomUUID() + "/refunds",
                headers(ops), "{\"amount_paise\": 100, \"reason\": \"x\"}"), 404, "NOT_FOUND");
        assertProblem("POST", REFUNDS, refund(rider, pending, 100), 403, "FORBIDDEN");
        assertProblem("POST", REFUNDS, postJson("/v1/ops/charges/" + pending.id() + "/refunds", headers(ops),
                "{\"amount_paise\": 0, \"reason\": \"x\"}"), 400, "VALIDATION_FAILED");
        assertProblem("POST", REFUNDS, postJson("/v1/ops/charges/" + pending.id() + "/refunds", headers(ops),
                "{\"amount_paise\": 100, \"reason\": \"\"}"), 400, "VALIDATION_FAILED");
        send();
        assertAnswered("POST", REFUNDS, refund(admin, reread(pending), 100), 202);
        assertThat(refundsOf(pending).getFirst().requestedBy()).isEqualTo("ADMIN:" + admin.id());
    }

    @Test
    void theSameKeyReplaysTheRefundWithoutRefundingAgain() {
        ChargeRow charge = paidCharge();
        String key = UUID.randomUUID().toString();
        String body = "{\"amount_paise\": 2500, \"reason\": \"waive part of the fare\"}";

        HttpResponse<String> first = postJson("/v1/ops/charges/" + charge.id() + "/refunds", headers(ops, key), body);
        HttpResponse<String> again = postJson("/v1/ops/charges/" + charge.id() + "/refunds", headers(ops, key), body);

        assertAnswered("POST", REFUNDS, again, 202);
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(again.headers().firstValue(Idempotency.REPLAYED_HEADER)).contains("true");
        assertThat(refundsOf(charge)).hasSize(1);
        assertThat(reread(charge).refundedPaise()).isEqualTo(2_500);
    }

    @Test
    void refundingAFeeReversesTheDriversShareInProportion() {
        AssignedRide ride = cancelledWithFee(riderWith("tok_ok"));
        send();
        ChargeRow fee = charge(ride.id(), "CANCELLATION_FEE");
        assertAnswered("POST", REFUNDS, refund(ops, fee, 2_499), 202);
        assertAnswered("POST", REFUNDS, refund(ops, fee, 2_501), 202);
        AssignedRide trip = completed(riderWith("tok_ok"));
        send();
        assertAnswered("POST", REFUNDS, refund(ops, fare(trip), 1_000), 202);
        ChargeRow driverless = pendingCharge(UUID.randomUUID(), null, "NO_SHOW_FEE", "tok_ok");
        send();
        assertAnswered("POST", REFUNDS, refund(ops, reread(driverless), 1_000), 202);

        send();

        assertThat(jdbc.sql("""
                        SELECT kind || ' ' || e.gross_paise || ' ' || e.commission_paise || ' ' || e.net_paise
                        FROM payment.driver_earnings e JOIN payment.refunds r ON r.id = e.source_id
                        WHERE r.charge_id = :id ORDER BY e.gross_paise DESC
                        """).param("id", fee.id()).query(String.class).list())
                .as("1,000 × 2,499 / 5,000 = 499.8 and 1,000 × 2,501 / 5,000 = 500.2, both rounded half up to 500")
                .containsExactly("ADJUSTMENT -2499 -500 -1999", "ADJUSTMENT -2501 -500 -2001");
        assertThat(jdbc.sql("SELECT count(*) FROM payment.driver_earnings WHERE ride_id IN (:ids)")
                .param("ids", List.of(trip.id(), driverless.rideId())).query(Long.class).single())
                .as("the fare's own row only: fare refunds and driverless fees leave earnings").isEqualTo(1);
        assertThat(refundsOf(driverless).getFirst().status()).isEqualTo("SUCCEEDED");
        assertThat(violations()).isEmpty();
    }

    @Test
    void concurrentRefundsNeverExceedTheCharge() throws InterruptedException {
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            ChargeRow charge = paidCharge();

            List<String> outcomes = RaceRunner.race(() -> status(refund(ops, charge, 6_000)),
                    () -> status(refund(ops, charge, 6_000)));

            assertThat(outcomes).containsExactlyInAnyOrder("202", "422 REFUND_EXCEEDS_CHARGE");
            assertThat(reread(charge).refundedPaise()).isEqualTo(6_000);
            assertThat(refundsOf(charge)).hasSize(1);
        }
        assertThat(violations()).isEmpty();
    }

    private List<String> audits(RefundRow refund) {
        return jdbc.sql("SELECT action FROM audit.audit_log WHERE entity_id = :id ORDER BY occurred_at, action")
                .param("id", refund.id().toString()).query(String.class).list();
    }

    private HttpResponse<String> refund(TestUser who, ChargeRow charge, long amountPaise) {
        return postJson("/v1/ops/charges/" + charge.id() + "/refunds", headers(who),
                "{\"amount_paise\": " + amountPaise + ", \"reason\": \"goodwill\"}");
    }

    private static Map<String, String> headers(TestUser who) {
        return headers(who, UUID.randomUUID().toString());
    }

    private static Map<String, String> headers(TestUser who, String key) {
        return Map.of("Authorization", who.authorization(), Idempotency.HEADER, key);
    }

    private static String status(HttpResponse<String> response) {
        return response.statusCode() == 202 ? "202"
                : response.statusCode() + " " + json(response).path("code").asString();
    }
}

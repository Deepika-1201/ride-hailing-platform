package com.ridehailing.payment.app;

import static com.ridehailing.support.EventContract.outboxEvents;
import static com.ridehailing.support.TestRides.asWorker;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.payment.db.AttemptRepository.AttemptRow;
import com.ridehailing.payment.db.ChargeRepository.ChargeRow;
import com.ridehailing.payment.db.RefundRepository.RefundRow;
import com.ridehailing.shared.Money;
import com.ridehailing.support.EventContract;
import com.ridehailing.support.TestRides.AssignedRide;
import com.ridehailing.support.TestUsers.TestUser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Late success (LLD §11.5, §11.10): a success reported for an attempt we had recorded as not received. It settles
 * unpaid dues, closes a dues attempt not yet sent, or, once the charge was paid again, is refunded automatically.
 */
class LateSuccessTests extends PaymentTest {

    @Test
    void aLateSuccessSettlesUnpaidDues() {
        TestUser rider = riderWith("tok_ok");
        AssignedRide ride = completed(rider);
        AttemptRow lost = notReceived(ride);

        reportSuccess(lost, fare(ride));

        ChargeRow charge = fare(ride);
        assertThat(charge.status()).isEqualTo("SUCCEEDED");
        assertThat(charge.succeededAttemptId()).isEqualTo(lost.id());
        assertThat(attempt(charge).status()).isEqualTo("SUCCEEDED");
        assertThat(refundsOf(charge)).isEmpty();
        assertThat(outboxEvents(jdbc, "ChargeSucceeded", charge.id())).hasSize(1);
        assertThat(getAs(rider.authorization(), "/v1/riders/me/dues").body()).contains("\"amount_paise\":0");
        assertThat(violations()).isEmpty();
    }

    @Test
    void aLateSuccessAfterTheRiderPaidAgainIsRefundedAutomatically() {
        TestUser rider = riderWith("tok_ok");
        AssignedRide ride = completed(rider);
        AttemptRow lost = notReceived(ride);
        assertThat(payDues(rider, "{}").statusCode()).isEqualTo(202);
        send();
        ChargeRow paid = fare(ride);
        assertThat(paid.status()).isEqualTo("SUCCEEDED");
        assertThat(paid.succeededAttemptId()).isNotEqualTo(lost.id());

        reportSuccess(lost, paid);

        RefundRow refund = refundsOf(paid).getFirst();
        assertThat(refundsOf(paid)).hasSize(1);
        assertThat(refund.automatic()).isTrue();
        assertThat(refund.attemptId()).isEqualTo(lost.id());
        assertThat(refund.amountPaise()).isEqualTo(paid.amountPaise());
        assertThat(refund.reason()).isEqualTo("LATE_SUCCESS");
        assertThat(reread(paid).refundedPaise()).as("operations may still refund the charge").isZero();
        assertThat(reread(paid).succeededAttemptId()).isEqualTo(paid.succeededAttemptId());
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_log WHERE entity_id = :id AND action = 'refund.automatic'")
                .param("id", refund.id().toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(violations()).as("paid twice, refunded once").isEmpty();

        assertThat(send()).isEqualTo(1);

        assertThat(refundsOf(paid).getFirst().status()).isEqualTo("SUCCEEDED");
        assertThat(outboxEvents(jdbc, "RefundSucceeded", refund.id())).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("automatic").asBoolean()).isTrue();
        });
        assertThat(jdbc.sql("SELECT count(*) FROM payment.driver_earnings WHERE source_id = :id")
                .param("id", refund.id()).query(Long.class).single()).as("no earnings adjustment").isZero();
        assertThat(violations()).isEmpty();
    }

    @Test
    void aLateSuccessWhileDuesArePendingSettlesTheChargeAndClosesTheUnsentAttempt() {
        TestUser rider = riderWith("tok_ok");
        AssignedRide ride = completed(rider);
        AttemptRow lost = notReceived(ride);
        assertThat(payDues(rider, "{}").statusCode()).isEqualTo(202);
        AttemptRow unsent = attempt(fare(ride));
        assertThat(unsent.status()).isEqualTo("PENDING");

        reportSuccess(lost, fare(ride));

        ChargeRow charge = fare(ride);
        assertThat(charge.status()).isEqualTo("SUCCEEDED");
        assertThat(charge.succeededAttemptId()).isEqualTo(lost.id());
        assertThat(attempt(charge).status()).isEqualTo("FAILED");
        assertThat(attempt(charge).failureCode()).isEqualTo("SUPERSEDED");
        assertThat(send()).isZero();
        assertThat(mock.calls(unsent.id())).isZero();
        assertThat(violations()).isEmpty();
    }

    @Test
    void aDeclineReportedForAFinalAttemptIsIgnored() {
        AssignedRide ride = completed(riderWith("tok_ok"));
        AttemptRow lost = notReceived(ride);

        assertThat(postWebhook(webhook("evt_" + lost.id(), "charge.failed", lost.id(), 1, "DECLINED")).statusCode())
                .isEqualTo(200);

        assertThat(attempt(fare(ride)).failureCode()).isEqualTo("NOT_RECEIVED");
        assertThat(jdbc.sql("SELECT outcome FROM payment.provider_webhooks WHERE provider_event_id = :id")
                .param("id", "evt_" + lost.id()).query(String.class).single()).isEqualTo("IGNORED");
    }

    @Test
    void aSuccessReportedForADeclinedAttemptIsIgnored() {
        AssignedRide ride = completed(riderWith("tok_decline"));
        send();
        AttemptRow declined = attempt(fare(ride));

        assertThat(postWebhook(webhook("evt_" + UUID.randomUUID(), "charge.succeeded", declined.id(), 1, null))
                .statusCode()).isEqualTo(200);

        assertThat(fare(ride).status()).isEqualTo("FAILED");
        assertThat(attempt(fare(ride)).status()).isEqualTo("FAILED");
        assertThat(refundsOf(fare(ride))).isEmpty();
    }

    @Test
    void afterALateSuccessTheOpenDuesAttemptNeverUndoesThePaidCharge() {
        TestUser undecided = riderWith("tok_ok");
        TestUser declining = riderWith("tok_ok");
        AssignedRide first = completed(undecided);
        AssignedRide second = completed(declining);
        AttemptRow lostFirst = notReceived(first.id(), "FARE");
        AttemptRow lostSecond = notReceived(second.id(), "FARE");
        profiles.setDefaultPaymentMethod(undecided.id(), card(undecided, "tok_webhook_only"));
        profiles.setDefaultPaymentMethod(declining.id(), card(declining, "tok_timeout_failed"));
        assertThat(payDues(undecided, "{}").statusCode()).isEqualTo(202);
        assertThat(payDues(declining, "{}").statusCode()).isEqualTo(202);
        send();
        AttemptRow openFirst = attempt(fare(first));
        AttemptRow openSecond = attempt(fare(second));
        assertThat(List.of(openFirst.status(), openSecond.status())).containsOnly("UNKNOWN");

        reportSuccess(lostFirst, fare(first));
        reportSuccess(lostSecond, fare(second));
        dueNow("payment.charge_attempts", openFirst.id());
        dueNow("payment.charge_attempts", openSecond.id());
        check();

        assertThat(attempt(fare(first)).status()).as("still undecided").isEqualTo("UNKNOWN");
        assertThat(attempt(fare(second)).status()).as("declined").isEqualTo("FAILED");
        assertThat(List.of(fare(first).status(), fare(second).status())).containsOnly("SUCCEEDED");
        assertThat(outboxEvents(jdbc, "ChargeFailed", fare(second).id())).as("only the first decline").hasSize(1);

        assertThat(postWebhook(webhook("evt_" + openFirst.id(), "charge.succeeded", openFirst.id(), 1, null))
                .statusCode()).isEqualTo(200);

        assertThat(attempt(fare(first)).status()).isEqualTo("SUCCEEDED");
        assertThat(refundsOf(fare(first))).singleElement().satisfies(refund -> {
            assertThat(refund.automatic()).isTrue();
            assertThat(refund.attemptId()).isEqualTo(openFirst.id());
        });
        assertThat(fare(first).succeededAttemptId()).isEqualTo(lostFirst.id());
        assertThat(violations()).isEmpty();
    }

    @Test
    void aLateSuccessOnAFeeIsRefundedWithoutTouchingTheDriversEarnings() {
        TestUser rider = riderWith("tok_ok");
        AssignedRide ride = cancelledWithFee(rider);
        AttemptRow lost = notReceived(ride.id(), "CANCELLATION_FEE");
        assertThat(payDues(rider, "{}").statusCode()).isEqualTo(202);
        send();
        ChargeRow fee = charge(ride.id(), "CANCELLATION_FEE");

        reportSuccess(lost, fee);
        send();

        assertThat(refundsOf(fee)).singleElement().satisfies(refund -> {
            assertThat(refund.automatic()).isTrue();
            assertThat(refund.status()).isEqualTo("SUCCEEDED");
        });
        assertThat(jdbc.sql("SELECT kind FROM payment.driver_earnings WHERE ride_id = :id").param("id", ride.id())
                .query(String.class).list()).containsExactly("CANCELLATION_FEE");
        assertThat(violations()).isEmpty();
    }

    @Test
    void aFailedAutomaticRefundReservesNothingToGiveBack() {
        TestUser rider = riderWith("tok_ok");
        AssignedRide ride = completed(rider);
        AttemptRow lost = notReceived(ride);
        assertThat(payDues(rider, "{}").statusCode()).isEqualTo(202);
        send();
        reportSuccess(lost, fare(ride));
        ScriptedProvider declining = new ScriptedProvider(mock);
        declining.refundAnswer = ProviderAnswer.failed("REFUND_DECLINED");

        assertThat(asWorker(executorWith(declining, properties)::sendNext)).isTrue();

        assertThat(refundsOf(fare(ride)).getFirst().status()).isEqualTo("FAILED");
        assertThat(fare(ride).refundedPaise()).isZero();
        assertThat(violations()).as("paid twice and not refunded: operations must act")
                .containsExactly("I7: charge " + fare(ride).id() + " was paid 2 times with 0 automatic refunds");
    }

    /** The first attempt's send dies before reaching the provider, and a check much later doesn't find it. */
    private AttemptRow notReceived(AssignedRide ride) {
        return notReceived(ride.id(), "FARE");
    }

    private AttemptRow notReceived(UUID rideId, String purpose) {
        ScriptedProvider scripted = new ScriptedProvider(mock);
        scripted.crashBeforeCall = true;
        assertThatThrownBy(() -> asWorker(executorWith(scripted, properties)::sendNext))
                .isInstanceOf(ScriptedProvider.SimulatedCrash.class);
        AttemptRow attempt = attempt(charge(rideId, purpose));
        jdbc.sql("""
                        UPDATE payment.charge_attempts
                        SET lease_until = now() - interval '1 second', sent_at = now() - interval '3 minutes'
                        WHERE id = :id
                        """)
                .param("id", attempt.id()).update();
        assertThat(check()).isEqualTo(1);
        AttemptRow lost = attempt(charge(rideId, purpose));
        assertThat(lost.failureCode()).isEqualTo("NOT_RECEIVED");
        assertThat(charge(rideId, purpose).status()).isEqualTo("FAILED");
        return lost;
    }

    /** The provider processed the attempt after all, and says so in a webhook. */
    private void reportSuccess(AttemptRow attempt, ChargeRow charge) {
        mock.charge(attempt.id(), new Money(charge.amountPaise(), charge.currency()), "tok_ok");
        assertThat(postWebhook(mock.webhookBody(attempt.id()).orElseThrow()).statusCode()).isEqualTo(200);
    }
}

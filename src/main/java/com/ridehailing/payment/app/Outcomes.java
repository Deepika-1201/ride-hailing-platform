package com.ridehailing.payment.app;

import static com.ridehailing.payment.app.ChargeCreation.amount;
import static com.ridehailing.payment.app.ChargeCreation.event;

import com.ridehailing.audit.AuditEntry;
import com.ridehailing.audit.AuditLog;
import com.ridehailing.payment.db.AttemptRepository;
import com.ridehailing.payment.db.AttemptRepository.AttemptRow;
import com.ridehailing.payment.db.ChargeRepository;
import com.ridehailing.payment.db.ChargeRepository.ChargeRow;
import com.ridehailing.payment.db.ProviderCallRepository;
import com.ridehailing.payment.db.ProviderCallRepository.Call;
import com.ridehailing.payment.db.RefundRepository;
import com.ridehailing.payment.db.RefundRepository.RefundRow;
import com.ridehailing.payment.events.ChargeFailed;
import com.ridehailing.payment.events.ChargeSucceeded;
import com.ridehailing.payment.events.RefundSucceeded;
import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.Transactions;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.Money;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Applies what is learned about an attempt or a refund: a send's answer, a status check or a webhook (LLD §11.2–§11.6,
 * §11.10). Each runs in one transaction that locks the charge, then the attempt or refund. The row's status decides,
 * not who answers, so a webhook before, after or instead of the response has the same effect.
 */
@Component
class Outcomes {

    static final String NOT_RECEIVED = "NOT_RECEIVED";
    static final String LATE_SUCCESS = "LATE_SUCCESS";

    private static final Logger log = LoggerFactory.getLogger(Outcomes.class);
    private static final String IN_FLIGHT = "IN_FLIGHT";
    private static final String UNKNOWN = "UNKNOWN";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";

    private final ChargeRepository charges;
    private final AttemptRepository attempts;
    private final RefundRepository refunds;
    private final ProviderCallRepository calls;
    private final Earnings earnings;
    private final Outbox outbox;
    private final AuditLog audit;
    private final PaymentMetrics metrics;
    private final PaymentProperties properties;
    private final Transactions transactions;

    Outcomes(ChargeRepository charges, AttemptRepository attempts, RefundRepository refunds,
            ProviderCallRepository calls, Earnings earnings, Outbox outbox, AuditLog audit, PaymentMetrics metrics,
            PaymentProperties properties, Transactions transactions) {
        this.charges = charges;
        this.attempts = attempts;
        this.refunds = refunds;
        this.calls = calls;
        this.earnings = earnings;
        this.outbox = outbox;
        this.audit = audit;
        this.metrics = metrics;
        this.properties = properties;
        this.transactions = transactions;
    }

    /** {@code answer} is null when a send or check had none. */
    Result attempt(UUID attemptId, Source source, ProviderAnswer answer) {
        return transactions.execute(() -> {
            Optional<UUID> chargeId = attempts.chargeOf(attemptId);
            if (chargeId.isEmpty()) {
                return Result.UNMATCHED;
            }
            ChargeRow charge = charges.lock(chargeId.get()).orElseThrow();
            AttemptRow attempt = attempts.lock(attemptId).orElseThrow();
            ProviderAnswer known = notReceived(source, attempt.status(), attempt.sentAt(), answer);
            if (known != null && known.isFinal()) {
                return finalAttempt(charge, attempt, known, source);
            }
            if (source == Source.SEND) {
                if (calls.noAnswer(Call.ATTEMPT, attemptId, properties.checkDelay(0))) {
                    metrics.recorded(PaymentMetrics.UNKNOWN);
                    charges.markUnknown(charge.id());
                }
            } else if (UNKNOWN.equals(attempt.status())) {
                scheduleNextCheck(Call.ATTEMPT, attemptId, attempt.sentAt(), attempt.checks());
                charges.markUnknown(charge.id());
            }
            return Result.IGNORED;
        });
    }

    /** {@code answer} is null when a send or check had none. */
    Result refund(UUID refundId, Source source, ProviderAnswer answer) {
        return transactions.execute(() -> {
            Optional<UUID> chargeId = refunds.chargeOf(refundId);
            if (chargeId.isEmpty()) {
                return Result.UNMATCHED;
            }
            ChargeRow charge = charges.lock(chargeId.get()).orElseThrow();
            RefundRow refund = refunds.lock(refundId).orElseThrow();
            ProviderAnswer known = notReceived(source, refund.status(), refund.sentAt(), answer);
            if (known != null && known.isFinal()) {
                return finalRefund(charge, refund, known, source);
            }
            if (source == Source.SEND) {
                calls.noAnswer(Call.REFUND, refundId, properties.checkDelay(0));
            } else if (UNKNOWN.equals(refund.status())) {
                scheduleNextCheck(Call.REFUND, refundId, refund.sentAt(), refund.checks());
            }
            return Result.IGNORED;
        });
    }

    private Result finalAttempt(ChargeRow charge, AttemptRow attempt, ProviderAnswer answer, Source source) {
        boolean open = IN_FLIGHT.equals(attempt.status()) || UNKNOWN.equals(attempt.status());
        boolean lateSuccess = answer.status() == ProviderAnswer.Status.SUCCEEDED && FAILED.equals(attempt.status())
                && NOT_RECEIVED.equals(attempt.failureCode());
        if (!open && !lateSuccess) {
            return Result.IGNORED;
        }
        Actor actor = source.actor;
        if (answer.status() == ProviderAnswer.Status.SUCCEEDED) {
            AttemptRow paid = attempts.succeed(attempt.id(), answer.reference());
            metrics.recorded(PaymentMetrics.SUCCEEDED);
            if (SUCCEEDED.equals(charge.status())) {
                RefundRow refund = refunds.insert(Ids.newId(), charge.id(), paid.id(), charge.amountPaise(),
                        LATE_SUCCESS, true, "SYSTEM:late-success");
                audit.record(new AuditEntry(actor, "refund.automatic", "refund", refund.id().toString(),
                        "attempt " + paid.id() + " succeeded after the charge was paid", null,
                        Map.of("charge_id", charge.id().toString(), "amount_paise", refund.amountPaise())));
                return Result.APPLIED;
            }
            ChargeRow succeeded = charges.succeed(charge.id(), paid.id());
            attempts.supersede(charge.id());
            earnings.fee(succeeded);
            outbox.append(event(succeeded, ChargeSucceeded.TYPE, ChargeSucceeded.VERSION, new ChargeSucceeded(
                    succeeded.id(), succeeded.rideId(), succeeded.riderId(), succeeded.driverId(), succeeded.purpose(),
                    amount(succeeded), succeeded.methodType(), paid.id(), succeeded.updatedAt())));
            audit.record(new AuditEntry(actor, "charge.succeeded", "charge", charge.id().toString(), null,
                    Map.of("status", charge.status()), Map.of("status", SUCCEEDED, "attempt_id", paid.id().toString())));
            return Result.APPLIED;
        }
        attempts.fail(attempt.id(), answer.failureCode());
        metrics.recorded(PaymentMetrics.FAILED);
        if (!SUCCEEDED.equals(charge.status())) {
            ChargeRow failed = charges.fail(charge.id(), answer.failureCode());
            outbox.append(event(failed, ChargeFailed.TYPE, ChargeFailed.VERSION, new ChargeFailed(failed.id(),
                    failed.rideId(), failed.riderId(), failed.purpose(), amount(failed), failed.methodType(),
                    answer.failureCode(), attempt.id(), failed.updatedAt())));
            audit.record(new AuditEntry(actor, "charge.failed", "charge", charge.id().toString(), answer.failureCode(),
                    Map.of("status", charge.status()), Map.of("status", FAILED, "attempt_id", attempt.id().toString())));
        }
        return Result.APPLIED;
    }

    private Result finalRefund(ChargeRow charge, RefundRow refund, ProviderAnswer answer, Source source) {
        if (!IN_FLIGHT.equals(refund.status()) && !UNKNOWN.equals(refund.status())) {
            if (answer.status() == ProviderAnswer.Status.SUCCEEDED && FAILED.equals(refund.status())) {
                log.error("Refund {} of charge {} succeeded at the provider after it was recorded as failed ({}); "
                        + "operations must reconcile it", refund.id(), charge.id(), refund.failureCode());
            }
            return Result.IGNORED;
        }
        Actor actor = source.actor;
        if (answer.status() == ProviderAnswer.Status.SUCCEEDED) {
            RefundRow done = refunds.succeed(refund.id(), answer.reference());
            outbox.append(new DomainEvent(RefundSucceeded.TYPE, RefundSucceeded.VERSION, "refund", done.id(),
                    done.version(), charge.rideId(), new RefundSucceeded(done.id(), charge.id(), charge.rideId(),
                            charge.riderId(), new Money(done.amountPaise(), charge.currency()), done.automatic(),
                            done.completedAt())));
            if (!done.automatic()) {
                earnings.adjustment(charge, done);
            }
            audit.record(new AuditEntry(actor, "refund.succeeded", "refund", refund.id().toString(), null,
                    Map.of("status", refund.status()), Map.of("status", SUCCEEDED)));
            return Result.APPLIED;
        }
        RefundRow failed = refunds.fail(refund.id(), answer.failureCode());
        if (!failed.automatic()) {
            charges.releaseRefund(charge.id(), failed.amountPaise());
        }
        audit.record(new AuditEntry(actor, "refund.failed", "refund", refund.id().toString(), answer.failureCode(),
                Map.of("status", refund.status()), Map.of("status", FAILED)));
        return Result.APPLIED;
    }

    /** A status check that didn't find a row sent long enough ago means the provider never received it (§11.3). */
    private ProviderAnswer notReceived(Source source, String status, Instant sentAt, ProviderAnswer answer) {
        boolean notFound = source == Source.CHECK && answer != null
                && answer.status() == ProviderAnswer.Status.NOT_FOUND && UNKNOWN.equals(status);
        if (notFound && sentAt.isBefore(charges.now().minus(properties.notReceivedAfter()))) {
            return ProviderAnswer.failed(NOT_RECEIVED);
        }
        return answer;
    }

    /** The schedule's next delay, or no further check once {@code check-for} has passed since the send. */
    private void scheduleNextCheck(Call call, UUID id, Instant sentAt, int checks) {
        if (!sentAt.plus(properties.checkFor()).isAfter(charges.now())) {
            calls.nextCheck(call, id, null);
            metrics.recorded(PaymentMetrics.UNRESOLVED);
            log.error("{} {} is still unknown {} after it was sent; it waits for operations", call, id,
                    properties.checkFor());
            return;
        }
        calls.nextCheck(call, id, properties.checkDelay(checks));
    }

    /** Who learned the outcome, for the audit log. */
    enum Source {
        SEND("payment-executor"),
        CHECK("payment-executor"),
        WEBHOOK("payment-webhook");

        private final Actor actor;

        Source(String component) {
            this.actor = Actor.system(component);
        }
    }

    enum Result {
        APPLIED,
        IGNORED,
        UNMATCHED
    }
}

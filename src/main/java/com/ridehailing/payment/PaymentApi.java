package com.ridehailing.payment;

import com.ridehailing.platform.Cursor;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.Money;
import com.ridehailing.shared.Page;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Payments, for the other modules (LLD §2.2, §11). */
public interface PaymentApi {

    /** The sum of the rider's {@code FAILED} charges, zero if none; booking reads it in its transaction (§11.7). */
    Money outstandingDues(UUID riderId);

    /** Operations' list of charges, newest first (§11.10). */
    Page<ChargeView> charges(ChargeFilter filter, Cursor after, int limit);

    /** The ride's charges with their attempts, and their refunds, oldest first (the timeline, §13.5). */
    RidePayments ofRide(UUID rideId);

    /**
     * Starts an operations refund of a succeeded online charge (§11.6); the executor sends it.
     *
     * @throws com.ridehailing.platform.ApiException {@code 404}, {@code 409 CHARGE_NOT_REFUNDABLE} or
     *     {@code 422 REFUND_EXCEEDS_CHARGE}
     */
    RefundView refund(UUID chargeId, long amountPaise, String reason, Actor ops);

    /** {@code status} and {@code unchangedFor} filter when not null. */
    record ChargeFilter(String status, Duration unchangedFor) {
    }

    /** The Charge schema of {@code openapi.yaml}. */
    record ChargeView(UUID id, UUID rideId, String purpose, Money amount, String status, String methodType,
            String failureCode, Instant createdAt, Instant updatedAt, UUID riderId, UUID driverId, Money refunded,
            List<AttemptView> attempts) {
    }

    record AttemptView(UUID id, int seq, String status, String failureCode, Instant createdAt, Instant completedAt) {
    }

    /** The Refund schema of {@code openapi.yaml}. */
    record RefundView(UUID id, UUID chargeId, Money amount, String status, String reason, boolean automatic,
            Instant createdAt, Instant completedAt) {
    }

    record RidePayments(List<ChargeView> charges, List<RefundView> refunds) {

        public RidePayments {
            charges = List.copyOf(charges);
            refunds = List.copyOf(refunds);
        }
    }
}

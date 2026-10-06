package com.ridehailing.payment.app;

import com.ridehailing.payment.PaymentApi;
import com.ridehailing.payment.db.AttemptRepository;
import com.ridehailing.payment.db.AttemptRepository.AttemptRow;
import com.ridehailing.payment.db.ChargeRepository;
import com.ridehailing.payment.db.ChargeRepository.ChargeRow;
import com.ridehailing.payment.db.RefundRepository;
import com.ridehailing.platform.Cursor;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.Money;
import com.ridehailing.shared.Page;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
class PaymentService implements PaymentApi {

    private final Dues dues;
    private final Refunds refunds;
    private final ChargeRepository charges;
    private final AttemptRepository attempts;
    private final RefundRepository refundRows;

    PaymentService(Dues dues, Refunds refunds, ChargeRepository charges, AttemptRepository attempts,
            RefundRepository refundRows) {
        this.dues = dues;
        this.refunds = refunds;
        this.charges = charges;
        this.attempts = attempts;
        this.refundRows = refundRows;
    }

    @Override
    public Money outstandingDues(UUID riderId) {
        return dues.outstanding(riderId);
    }

    @Override
    public Page<ChargeView> charges(ChargeFilter filter, Cursor after, int limit) {
        List<ChargeRow> rows = charges.list(filter.status(), filter.unchangedFor(), after, limit + 1);
        List<ChargeRow> page = rows.subList(0, Math.min(limit, rows.size()));
        Map<UUID, List<AttemptView>> byCharge = attempts.ofCharges(page.stream().map(ChargeRow::id).toList()).stream()
                .collect(Collectors.groupingBy(AttemptRow::chargeId,
                        Collectors.mapping(PaymentService::attempt, Collectors.toList())));
        String next = null;
        if (rows.size() > limit) {
            ChargeRow last = page.getLast();
            next = new Cursor(last.createdAt(), last.id()).encode();
        }
        return new Page<>(page.stream().map(charge -> view(charge, byCharge.getOrDefault(charge.id(), List.of())))
                .toList(), next);
    }

    @Override
    public RidePayments ofRide(UUID rideId) {
        List<ChargeRow> rows = charges.ofRide(rideId);
        Map<UUID, List<AttemptView>> byCharge = attempts.ofCharges(rows.stream().map(ChargeRow::id).toList()).stream()
                .collect(Collectors.groupingBy(AttemptRow::chargeId,
                        Collectors.mapping(PaymentService::attempt, Collectors.toList())));
        List<RefundView> refunded = rows.stream().flatMap(charge -> refundRows.ofCharge(charge.id()).stream()
                .map(refund -> Refunds.view(refund, charge.currency()))).toList();
        return new RidePayments(rows.stream().map(charge -> view(charge, byCharge.getOrDefault(charge.id(),
                List.of()))).toList(), refunded);
    }

    @Override
    public RefundView refund(UUID chargeId, long amountPaise, String reason, Actor ops) {
        return refunds.refund(chargeId, amountPaise, reason, ops);
    }

    private static ChargeView view(ChargeRow charge, List<AttemptView> attempts) {
        return new ChargeView(charge.id(), charge.rideId(), charge.purpose(), ChargeCreation.amount(charge),
                charge.status(), charge.methodType(), charge.failureCode(), charge.createdAt(), charge.updatedAt(),
                charge.riderId(), charge.driverId(), new Money(charge.refundedPaise(), charge.currency()), attempts);
    }

    private static AttemptView attempt(AttemptRow attempt) {
        return new AttemptView(attempt.id(), attempt.seq(), attempt.status(), attempt.failureCode(),
                attempt.createdAt(), attempt.completedAt());
    }
}

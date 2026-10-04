package com.ridehailing.payment.app;

import com.ridehailing.audit.AuditEntry;
import com.ridehailing.audit.AuditLog;
import com.ridehailing.payment.PaymentApi.RefundView;
import com.ridehailing.payment.db.ChargeRepository;
import com.ridehailing.payment.db.ChargeRepository.ChargeRow;
import com.ridehailing.payment.db.RefundRepository;
import com.ridehailing.payment.db.RefundRepository.RefundRow;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Transactions;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.Money;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Operations refunds (FR-PY5, LLD §11.6): reserved on the charge under its lock, then sent by the executor. */
@Component
class Refunds {

    private final ChargeRepository charges;
    private final RefundRepository refunds;
    private final AuditLog audit;
    private final Transactions transactions;

    Refunds(ChargeRepository charges, RefundRepository refunds, AuditLog audit, Transactions transactions) {
        this.charges = charges;
        this.refunds = refunds;
        this.audit = audit;
        this.transactions = transactions;
    }

    RefundView refund(UUID chargeId, long amountPaise, String reason, Actor ops) {
        return transactions.execute(() -> {
            ChargeRow charge = charges.lock(chargeId).orElseThrow(ApiException::notFound);
            if (!"SUCCEEDED".equals(charge.status()) || ChargeCreation.CASH.equals(charge.methodType())) {
                throw new ApiException(HttpStatus.CONFLICT, "CHARGE_NOT_REFUNDABLE",
                        "Only a succeeded online charge can be refunded.");
            }
            if (!charges.reserveRefund(chargeId, amountPaise)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "REFUND_EXCEEDS_CHARGE",
                        "At most " + (charge.amountPaise() - charge.refundedPaise())
                                + " paise of this charge can still be refunded.");
            }
            RefundRow refund = refunds.insert(Ids.newId(), chargeId, charge.succeededAttemptId(), amountPaise, reason,
                    false, ops.type() + ":" + ops.id());
            audit.record(new AuditEntry(ops, "refund.requested", "refund", refund.id().toString(), reason, null,
                    Map.of("charge_id", chargeId.toString(), "amount_paise", amountPaise)));
            return view(refund, charge.currency());
        });
    }

    static RefundView view(RefundRow refund, String currency) {
        return new RefundView(refund.id(), refund.chargeId(), new Money(refund.amountPaise(), currency),
                refund.status(), refund.reason(), refund.automatic(), refund.createdAt(), refund.completedAt());
    }
}

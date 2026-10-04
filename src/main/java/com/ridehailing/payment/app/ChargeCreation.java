package com.ridehailing.payment.app;

import com.ridehailing.audit.AuditEntry;
import com.ridehailing.audit.AuditLog;
import com.ridehailing.payment.db.AttemptRepository;
import com.ridehailing.payment.db.ChargeRepository;
import com.ridehailing.payment.db.ChargeRepository.ChargeRow;
import com.ridehailing.payment.db.ChargeRepository.NewCharge;
import com.ridehailing.payment.events.ChargeFailed;
import com.ridehailing.payment.events.ChargeSucceeded;
import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.Outbox;
import com.ridehailing.rider.RiderApi;
import com.ridehailing.rider.RiderApi.PaymentMethodRef;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.Money;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Creates the charge for a completed trip or a fee (LLD §11.1, §11.10), in the consumer's transaction. The charge is
 * unique per ride and purpose, so a redelivered or replayed event finds it and stops.
 */
@Component
class ChargeCreation {

    static final String CASH = "CASH";
    static final String NO_PAYMENT_METHOD = "NO_PAYMENT_METHOD";
    private static final Actor CHARGES = Actor.system("payment.charges");

    private final ChargeRepository charges;
    private final AttemptRepository attempts;
    private final RiderApi riders;
    private final PaymentProvider provider;
    private final Outbox outbox;
    private final AuditLog audit;

    ChargeCreation(ChargeRepository charges, AttemptRepository attempts, RiderApi riders, PaymentProvider provider,
            Outbox outbox, AuditLog audit) {
        this.charges = charges;
        this.attempts = attempts;
        this.riders = riders;
        this.provider = provider;
        this.outbox = outbox;
        this.audit = audit;
    }

    /** {@code methodId} and {@code methodType} are the ride's, captured at booking; a cash method is never charged. */
    void create(Due due) {
        if (Earnings.FARE.equals(due.purpose()) && CASH.equals(due.methodType())) {
            charges.insert(charge(due, CASH, due.methodId(), "SUCCEEDED", null)).ifPresent(cash -> outbox.append(
                    event(cash, ChargeSucceeded.TYPE, ChargeSucceeded.VERSION, new ChargeSucceeded(cash.id(),
                            cash.rideId(), cash.riderId(), cash.driverId(), cash.purpose(), amount(cash),
                            cash.methodType(), null, cash.createdAt()))));
            return;
        }
        Optional<PaymentMethodRef> method = riders.onlineMethod(due.riderId(), due.methodId());
        if (method.isEmpty()) {
            charges.insert(charge(due, due.methodType(), null, "FAILED", NO_PAYMENT_METHOD)).ifPresent(failed -> {
                outbox.append(event(failed, ChargeFailed.TYPE, ChargeFailed.VERSION, new ChargeFailed(failed.id(),
                        failed.rideId(), failed.riderId(), failed.purpose(), amount(failed), failed.methodType(),
                        NO_PAYMENT_METHOD, null, failed.createdAt())));
                audit.record(new AuditEntry(CHARGES, "charge.failed", "charge", failed.id().toString(),
                        NO_PAYMENT_METHOD, null, Map.of("status", failed.status())));
            });
            return;
        }
        PaymentMethodRef online = method.get();
        charges.insert(charge(due, online.type(), online.id(), "PENDING", null)).ifPresent(pending ->
                attempts.insert(Ids.newId(), pending.id(), provider.name(), online.id(), online.providerRef()));
    }

    private static NewCharge charge(Due due, String methodType, UUID methodId, String status, String failureCode) {
        return new NewCharge(Ids.newId(), due.rideId(), due.riderId(), due.driverId(), due.cityId(), due.purpose(),
                due.amount().amountPaise(), due.commission().amountPaise(), due.amount().currency(), methodType,
                methodId, status, failureCode);
    }

    static Money amount(ChargeRow charge) {
        return new Money(charge.amountPaise(), charge.currency());
    }

    /** Charge events are ordered by ride, as offer and ride events are (LLD §15.1). */
    static DomainEvent event(ChargeRow charge, String type, int version, Object payload) {
        return new DomainEvent(type, version, "charge", charge.id(), charge.version(), charge.rideId(), payload);
    }

    /** What a ride's event says is due. */
    record Due(UUID rideId, UUID riderId, UUID driverId, String cityId, String purpose, Money amount, Money commission,
            UUID methodId, String methodType) {
    }
}

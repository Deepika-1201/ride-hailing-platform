package com.ridehailing.payment.app;

import com.ridehailing.audit.AuditEntry;
import com.ridehailing.audit.AuditLog;
import com.ridehailing.payment.db.AttemptRepository;
import com.ridehailing.payment.db.ChargeRepository;
import com.ridehailing.payment.db.ChargeRepository.ChargeRow;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Transactions;
import com.ridehailing.rider.RiderApi;
import com.ridehailing.rider.RiderApi.PaymentMethodRef;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Rider dues (FR-PY3, FR-R4, LLD §11.7, §11.10): the rider's {@code FAILED} charges, paid with a new attempt each. */
@Service
public class Dues {

    private final ChargeRepository charges;
    private final AttemptRepository attempts;
    private final RiderApi riders;
    private final PaymentProvider provider;
    private final AuditLog audit;
    private final Transactions transactions;
    private final PaymentProperties properties;

    Dues(ChargeRepository charges, AttemptRepository attempts, RiderApi riders, PaymentProvider provider,
            AuditLog audit, Transactions transactions, PaymentProperties properties) {
        this.charges = charges;
        this.attempts = attempts;
        this.riders = riders;
        this.provider = provider;
        this.audit = audit;
        this.transactions = transactions;
        this.properties = properties;
    }

    public DuesView of(UUID riderId) {
        return view(charges.failed(riderId));
    }

    Money outstanding(UUID riderId) {
        return total(charges.failed(riderId));
    }

    /**
     * A new attempt on every failed charge with the rider's card or UPI method, or their default.
     *
     * @throws ApiException {@code 409 NO_DUES}, or {@code 422 PAYMENT_METHOD_INVALID} for cash or a method that isn't
     *     the rider's active one
     */
    public DuesView pay(UUID riderId, UUID methodId) {
        return transactions.execute(() -> {
            List<ChargeRow> failed = charges.lockFailed(riderId);
            if (failed.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "NO_DUES", "Nothing is owed.");
            }
            PaymentMethodRef method = riders.paymentMethod(riderId, methodId)
                    .filter(found -> !ChargeCreation.CASH.equals(found.type()))
                    .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PAYMENT_METHOD_INVALID",
                            "Dues are paid with an active card or UPI method of yours."));
            List<ChargeRow> paying = failed.stream().map(charge -> {
                attempts.insert(Ids.newId(), charge.id(), provider.name(), method.id(), method.providerRef());
                return charges.pay(charge.id(), method.type(), method.id());
            }).toList();
            audit.record(new AuditEntry(new Actor(Actor.Type.RIDER, riderId.toString()), "dues.pay", "rider",
                    riderId.toString(), null, null, Map.of("charges", paying.size(), "payment_method_id",
                            method.id().toString())));
            return view(paying);
        });
    }

    private DuesView view(List<ChargeRow> rows) {
        return new DuesView(total(rows), rows.stream().map(Dues::summary).toList());
    }

    private Money total(List<ChargeRow> rows) {
        Set<String> currencies = rows.stream().map(ChargeRow::currency).collect(Collectors.toSet());
        if (currencies.size() > 1) {
            throw new IllegalStateException("Dues in more than one currency: " + currencies);
        }
        String currency = currencies.isEmpty() ? properties.currency() : currencies.iterator().next();
        return new Money(rows.stream().mapToLong(ChargeRow::amountPaise).sum(), currency);
    }

    static ChargeSummary summary(ChargeRow charge) {
        return new ChargeSummary(charge.id(), charge.rideId(), charge.purpose(), ChargeCreation.amount(charge),
                charge.status(), charge.methodType(), charge.failureCode(), charge.createdAt(), charge.updatedAt());
    }

    /** The Dues schema of {@code openapi.yaml}. */
    public record DuesView(Money total, List<ChargeSummary> charges) {
    }

    /** The ChargeSummary schema. */
    public record ChargeSummary(UUID id, UUID rideId, String purpose, Money amount, String status, String methodType,
            String failureCode, Instant createdAt, Instant updatedAt) {
    }
}

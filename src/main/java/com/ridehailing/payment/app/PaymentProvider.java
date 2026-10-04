package com.ridehailing.payment.app;

import com.ridehailing.shared.Money;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A payment provider (ADR-014). Calls run outside any transaction; an exception from {@link #charge}, {@link #refund}
 * or {@link #status} means the outcome is unknown, never that nothing happened (LLD §11.2).
 */
interface PaymentProvider {

    String name();

    /** Charges the method once per {@code key}: a repeated key answers the first outcome without charging again. */
    ProviderAnswer charge(UUID key, Money amount, String methodRef);

    /** Refunds part or all of the payment made with {@code paymentKey}, once per {@code key}. */
    ProviderAnswer refund(UUID key, UUID paymentKey, Money amount);

    /** What the provider knows of the charge or refund made with {@code key}. */
    ProviderAnswer status(UUID key);

    /** The signed time, if {@code signature} (an {@code X-Signature} header) signs exactly {@code body}. */
    Optional<Instant> verify(String signature, byte[] body);
}

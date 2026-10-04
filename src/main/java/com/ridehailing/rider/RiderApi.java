package com.ridehailing.rider;

import java.util.Optional;
import java.util.UUID;

/** Riders, for the other modules (LLD §2.2). Every method creates the rider on first use, as profiles do (§13.3). */
public interface RiderApi {

    /** The rider's active method, or their default when {@code methodId} is null; empty if not theirs or inactive. */
    Optional<PaymentMethodRef> paymentMethod(UUID riderId, UUID methodId);

    /**
     * The method to charge online (LLD §11.10): {@code preferredId} while it is the rider's, active and not cash; else
     * their default unless it is cash; else their newest card or UPI method. Empty when they have none.
     */
    Optional<PaymentMethodRef> onlineMethod(UUID riderId, UUID preferredId);

    /** What a ride keeps of its rider. */
    RiderSnapshot snapshot(UUID riderId);

    /** {@code providerRef} is the provider's token for a card or UPI method; null for cash. */
    record PaymentMethodRef(UUID id, String type, String providerRef) {
    }

    /** {@code firstName} is null until the rider sets it; ratings join it in phase 10. */
    record RiderSnapshot(UUID riderId, String firstName) {
    }
}

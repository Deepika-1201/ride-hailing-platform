package com.ridehailing.rider;

import java.util.Optional;
import java.util.UUID;

/** Riders, for the other modules (LLD §2.2). Both methods create the rider on first use, as profiles do (§13.3). */
public interface RiderApi {

    /** The rider's active method, or their default when {@code methodId} is null; empty if not theirs or inactive. */
    Optional<PaymentMethodRef> paymentMethod(UUID riderId, UUID methodId);

    /** What a ride keeps of its rider. */
    RiderSnapshot snapshot(UUID riderId);

    record PaymentMethodRef(UUID id, String type) {
    }

    /** {@code firstName} is null until the rider sets it; ratings join it in phase 10. */
    record RiderSnapshot(UUID riderId, String firstName) {
    }
}

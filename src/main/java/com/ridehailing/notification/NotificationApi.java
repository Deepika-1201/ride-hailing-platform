package com.ridehailing.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Messages to users (LLD §15.4). */
public interface NotificationApi {

    /** Sends a sign-in code by SMS, synchronously; the code is never stored or queued (LLD §12.1). */
    void sendOneTimeCode(String phone, String code);

    /** The ride's notifications with their deliveries, oldest first (the timeline, LLD §13.5). */
    List<NotificationView> ofRide(UUID rideId);

    /** Tells the rider their driver is about to arrive, once per ride whoever calls (LLD §9.7). */
    void driverArriving(UUID rideId, UUID riderId);

    record NotificationView(UUID id, UUID recipientId, String kind, Instant createdAt,
            List<DeliveryView> deliveries) {

        public NotificationView {
            deliveries = List.copyOf(deliveries);
        }
    }

    /** {@code sentAt} and {@code lastError} may be null. */
    record DeliveryView(String channel, String status, int attempts, Instant sentAt, String lastError) {
    }
}

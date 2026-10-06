package com.ridehailing.notification.app;

import static com.ridehailing.notification.app.Notifications.payload;
import static com.ridehailing.notification.app.Notifications.uuid;

import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.EventEnvelope;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** {@code notification.rides} (LLD §15.2, §15.4): tells the rider, and the driver where it concerns them. */
@Component
class RideNotifications implements EventConsumer {

    static final String NAME = "notification.rides";

    private final Notifications notifications;

    RideNotifications(Notifications notifications) {
        this.notifications = notifications;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("DriverAssigned", "DriverUnassigned", "DriverArrived", "TripStarted", "TripCompleted",
                "RideCancelled", "RideNotMatched");
    }

    @Override
    public void handle(EventEnvelope event) {
        JsonNode ride = event.payload();
        UUID rideId = uuid(ride, "ride_id");
        UUID riderId = uuid(ride, "rider_id");
        ObjectNode payload = payload().put("ride_id", rideId.toString());
        switch (event.eventType()) {
            case "DriverAssigned" -> notify(event, riderId, NotificationKind.DRIVER_ASSIGNED, rideId, payload);
            case "DriverUnassigned" -> {
                String reason = ride.get("reason").asString();
                payload.put("reason", reason);
                notify(event, riderId, NotificationKind.DRIVER_UNASSIGNED, rideId, payload);
                if ("DRIVER_UNREACHABLE".equals(reason)) {
                    notify(event, uuid(ride, "driver_id"), NotificationKind.DRIVER_UNASSIGNED, rideId, payload);
                }
            }
            case "DriverArrived" -> notify(event, riderId, NotificationKind.DRIVER_ARRIVED, rideId, payload);
            case "TripStarted" -> notify(event, riderId, NotificationKind.TRIP_STARTED, rideId, payload);
            case "TripCompleted" -> {
                payload.set("fare", ride.get("fare"));
                notify(event, riderId, NotificationKind.TRIP_COMPLETED, rideId, payload);
                notify(event, uuid(ride, "driver_id"), NotificationKind.TRIP_COMPLETED, rideId, payload);
            }
            case "RideCancelled" -> cancelled(event, ride, rideId, riderId, payload);
            case "RideNotMatched" -> notify(event, riderId, NotificationKind.NO_DRIVER_FOUND, rideId, payload);
            default -> throw new IllegalArgumentException("Not a ride event for notifications: " + event.eventType());
        }
    }

    /** Whoever didn't cancel; both after operations cancelled. A ride cancelled while searching has no driver. */
    private void cancelled(EventEnvelope event, JsonNode ride, UUID rideId, UUID riderId, ObjectNode payload) {
        String cancelledBy = ride.get("cancelled_by").asString();
        payload.put("cancelled_by", cancelledBy);
        if (ride.hasNonNull("reason")) {
            payload.put("reason", ride.get("reason").asString());
        }
        if (ride.hasNonNull("fee")) {
            payload.putObject("fee").put("purpose", ride.get("fee").get("purpose").asString())
                    .set("amount", ride.get("fee").get("amount"));
        }
        if (!"RIDER".equals(cancelledBy)) {
            notify(event, riderId, NotificationKind.RIDE_CANCELLED, rideId, payload);
        }
        if (!"DRIVER".equals(cancelledBy) && ride.hasNonNull("driver_id")) {
            notify(event, uuid(ride, "driver_id"), NotificationKind.RIDE_CANCELLED, rideId, payload);
        }
    }

    private void notify(EventEnvelope event, UUID recipientId, NotificationKind kind, UUID rideId,
            ObjectNode payload) {
        notifications.notify(event, recipientId, kind, rideId, payload);
    }
}

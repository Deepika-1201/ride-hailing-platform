package com.ridehailing.platform.outbox;

import java.util.Set;

final class KafkaTopics {

    private KafkaTopics() {
    }

    static Set<String> eventTopics() {
        return Set.of("rides.events", "dispatch.events", "drivers.events", "payments.events");
    }

    static String forEvent(String eventType) {
        return switch (eventType) {
            case "RideRequested", "DriverAssigned", "DriverUnassigned", "DriverArrived", "TripStarted",
                    "TripCompleted", "RideCancelled", "RideNotMatched", "RatingSubmitted" -> "rides.events";
            case "OfferCreated", "OfferAccepted", "OfferDeclined", "OfferExpired", "OfferWithdrawn" -> "dispatch.events";
            case "DriverWentOnline", "DriverWentOffline", "DriverVerified", "DriverSuspended", "DriverReinstated" -> "drivers.events";
            case "ChargeSucceeded", "ChargeFailed", "RefundSucceeded" -> "payments.events";
            default -> throw new IllegalArgumentException("No Kafka topic for event type " + eventType);
        };
    }
}
package com.ridehailing.notification.push;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** A push notification to one user's apps; {@code rideId} may be null. */
public record Push(UUID deliveryId, UUID recipientId, String kind, UUID rideId, JsonNode payload) {
}

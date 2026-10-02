package com.ridehailing.platform;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** A timer being fired; {@code attempts} counts earlier failed firings. */
public record DueTimer(UUID id, String kind, UUID aggregateId, JsonNode payload, Instant dueAt, int attempts) {
}

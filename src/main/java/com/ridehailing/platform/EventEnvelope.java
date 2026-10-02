package com.ridehailing.platform;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** An event as stored and delivered: the envelope of {@code schemas/events/envelope.v1.json} (LLD §15). */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        UUID partitionKey,
        Instant occurredAt,
        String producer,
        String correlationId,
        String causationId,
        String traceParent,
        JsonNode payload) {
}

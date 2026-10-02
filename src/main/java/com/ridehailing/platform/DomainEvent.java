package com.ridehailing.platform;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A fact to publish: its type and schema version, the aggregate it happened to, and a payload record serialized as
 * the event's JSON schema describes. {@code partitionKey} orders related events; it is the ride for ride, offer and
 * payment events.
 */
public record DomainEvent(
        String type,
        int version,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        UUID partitionKey,
        Object payload) {

    private static final Pattern TYPE = Pattern.compile("[A-Z][A-Za-z]{2,60}");

    public DomainEvent {
        if (type == null || !TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("Event types are PascalCase names: " + type);
        }
        if (version < 1 || aggregateVersion < 0) {
            throw new IllegalArgumentException("Versions start at 1, aggregate versions at 0");
        }
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("An event needs an aggregate type");
        }
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(partitionKey, "partitionKey");
        Objects.requireNonNull(payload, "payload");
    }

    /** An event ordered with the other events of its own aggregate. */
    public static DomainEvent of(
            String type, int version, String aggregateType, UUID aggregateId, long aggregateVersion, Object payload) {
        return new DomainEvent(type, version, aggregateType, aggregateId, aggregateVersion, aggregateId, payload);
    }
}

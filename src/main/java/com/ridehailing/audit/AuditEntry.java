package com.ridehailing.audit;

import com.ridehailing.shared.Actor;
import java.util.Map;
import java.util.Objects;

/**
 * One audited action, such as {@code ride.cancel} on a ride. {@code before} and {@code after} hold the changed fields
 * only and are optional, like {@code reason}; phone numbers, PINs and positions never go in them (LLD §5.6).
 */
public record AuditEntry(
        Actor actor,
        String action,
        String entityType,
        String entityId,
        String reason,
        Map<String, Object> before,
        Map<String, Object> after) {

    public AuditEntry {
        Objects.requireNonNull(actor, "actor");
        requireText(action, "action");
        requireText(entityType, "entityType");
        requireText(entityId, "entityId");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
    }
}

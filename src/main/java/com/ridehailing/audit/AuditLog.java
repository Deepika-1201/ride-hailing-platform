package com.ridehailing.audit;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** The append-only audit log (FR-A1, LLD §5.6). */
public interface AuditLog {

    /**
     * Records {@code entry} in the caller's transaction, with the request and correlation IDs of the logging context.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException if there is no transaction
     */
    void record(AuditEntry entry);

    /** Entries about the entities of the type, oldest first (a ride's timeline, LLD §13.5). */
    List<AuditRecord> entries(String entityType, Collection<String> entityIds);

    /** A stored entry; {@code before} and {@code after} hold the changed fields and may be null. */
    record AuditRecord(Instant occurredAt, String actorType, String actorId, String action, String entityType,
            String entityId, String reason, JsonNode before, JsonNode after, String requestId, String correlationId) {
    }
}

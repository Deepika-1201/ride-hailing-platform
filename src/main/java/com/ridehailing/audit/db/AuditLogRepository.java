package com.ridehailing.audit.db;

import com.ridehailing.audit.AuditEntry;
import com.ridehailing.audit.AuditLog.AuditRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class AuditLogRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    AuditLogRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(UUID id, Instant occurredAt, AuditEntry entry, String requestId, String correlationId) {
        jdbc.sql("""
                        INSERT INTO audit.audit_log (id, occurred_at, actor_type, actor_id, action, entity_type,
                                                     entity_id, reason, request_id, correlation_id,
                                                     before_state, after_state)
                        VALUES (:id, :occurredAt, :actorType, :actorId, :action, :entityType,
                                :entityId, :reason, :requestId, :correlationId,
                                CAST(:before AS jsonb), CAST(:after AS jsonb))
                        """)
                .param("id", id)
                .param("occurredAt", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .param("actorType", entry.actor().type().name())
                .param("actorId", entry.actor().id())
                .param("action", entry.action())
                .param("entityType", entry.entityType())
                .param("entityId", entry.entityId())
                .param("reason", entry.reason())
                .param("requestId", requestId)
                .param("correlationId", correlationId)
                .param("before", toJson(entry.before()))
                .param("after", toJson(entry.after()))
                .update();
    }

    /** Entries about the entities, oldest first; {@code before} and {@code after} may be null. */
    public List<AuditRecord> entries(String entityType, Collection<String> entityIds) {
        return jdbc.sql("""
                        SELECT occurred_at, actor_type, actor_id, action, entity_type, entity_id, reason,
                               before_state::text AS before_state, after_state::text AS after_state, request_id,
                               correlation_id
                        FROM audit.audit_log WHERE entity_type = :entityType AND entity_id = ANY(:entityIds)
                        ORDER BY occurred_at, id
                        """)
                .param("entityType", entityType)
                .param("entityIds", new SqlArrayValue("text", entityIds.toArray()))
                .query((row, rowNumber) -> new AuditRecord(row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        row.getString("actor_type"), row.getString("actor_id"), row.getString("action"),
                        row.getString("entity_type"), row.getString("entity_id"), row.getString("reason"),
                        state(row.getString("before_state")), state(row.getString("after_state")),
                        row.getString("request_id"), row.getString("correlation_id")))
                .list();
    }

    private JsonNode state(String text) {
        return text == null ? null : json.readTree(text);
    }

    private String toJson(Map<String, Object> state) {
        return state == null ? null : json.writeValueAsString(state);
    }
}

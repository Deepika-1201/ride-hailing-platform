package com.ridehailing.audit.db;

import com.ridehailing.audit.AuditEntry;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
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

    private String toJson(Map<String, Object> state) {
        return state == null ? null : json.writeValueAsString(state);
    }
}

package com.ridehailing.platform.timers;

import com.ridehailing.platform.TimerKind;
import com.ridehailing.platform.Timers;
import com.ridehailing.shared.Ids;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
class JdbcTimers implements Timers {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcTimers(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void schedule(TimerKind kind, UUID aggregateId, Instant dueAt, Map<String, Object> payload) {
        jdbc.sql("""
                        INSERT INTO platform.timers (id, kind, aggregate_id, payload, due_at)
                        VALUES (:id, :kind, :aggregateId, CAST(:payload AS jsonb), :dueAt)
                        """)
                .param("id", Ids.newId())
                .param("kind", kind.name())
                .param("aggregateId", aggregateId)
                .param("payload", json.writeValueAsString(payload))
                .param("dueAt", OffsetDateTime.ofInstant(dueAt, ZoneOffset.UTC))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void scheduleAfter(TimerKind kind, UUID aggregateId, Duration delay, Map<String, Object> payload) {
        jdbc.sql("""
                        INSERT INTO platform.timers (id, kind, aggregate_id, payload, due_at)
                        VALUES (:id, :kind, :aggregateId, CAST(:payload AS jsonb),
                                now() + make_interval(secs => :delayS))
                        """)
                .param("id", Ids.newId())
                .param("kind", kind.name())
                .param("aggregateId", aggregateId)
                .param("payload", json.writeValueAsString(payload))
                .param("delayS", delay.toMillis() / 1000.0)
                .update();
    }

    @Override
    public Set<UUID> scheduled(TimerKind kind, Collection<UUID> aggregateIds) {
        if (aggregateIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                        SELECT DISTINCT aggregate_id FROM platform.timers
                        WHERE kind = :kind AND aggregate_id = ANY(:aggregateIds)
                        """)
                .param("kind", kind.name())
                .param("aggregateIds", new SqlArrayValue("uuid", aggregateIds.toArray()))
                .query(UUID.class)
                .list());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancel(TimerKind kind, UUID aggregateId) {
        return jdbc.sql("""
                        DELETE FROM platform.timers WHERE id IN (
                            SELECT id FROM platform.timers
                            WHERE aggregate_id = :aggregateId AND kind = :kind
                            FOR UPDATE SKIP LOCKED)
                        """)
                .param("aggregateId", aggregateId)
                .param("kind", kind.name())
                .update();
    }
}

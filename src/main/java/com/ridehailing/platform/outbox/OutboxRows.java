package com.ridehailing.platform.outbox;

import com.ridehailing.platform.EventEnvelope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Reads outbox rows as envelopes, and marks them published under the relay's fencing token. */
@Component
class OutboxRows {

    private static final String COLUMNS = """
            SELECT id, event_id, event_type, event_version, aggregate_type, aggregate_id, aggregate_version,
                   partition_key, occurred_at, producer, correlation_id, causation_id, trace_parent, payload::text
            FROM platform.outbox
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    OutboxRows(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** By ID, not by a high-water mark, so a late commit with a lower ID is picked up by the next batch (LLD §5.2). */
    List<Row> unpublished(int limit) {
        return jdbc.sql(COLUMNS + "WHERE published_at IS NULL ORDER BY id LIMIT :limit")
                .param("limit", limit)
                .query(this::row)
                .list();
    }

    Optional<Row> byEventId(UUID eventId) {
        return jdbc.sql(COLUMNS + "WHERE event_id = :eventId").param("eventId", eventId).query(this::row).optional();
    }

    List<Row> byPartitionKey(UUID key) {
        return jdbc.sql(COLUMNS + "WHERE partition_key = :key ORDER BY id").param("key", key).query(this::row).list();
    }

    /** Marks rows published only while {@code holder} still has the lease with {@code token}, in one statement. */
    int markPublished(List<Long> ids, String lease, String holder, long token) {
        return jdbc.sql("""
                        UPDATE platform.outbox SET published_at = now()
                        WHERE id IN (:ids) AND published_at IS NULL
                          AND EXISTS (SELECT 1 FROM platform.leases
                                      WHERE name = :lease AND holder = :holder AND token = :token
                                        AND expires_at >= now())
                        """)
                .param("ids", ids)
                .param("lease", lease)
                .param("holder", holder)
                .param("token", token)
                .update();
    }

    private Row row(ResultSet row, int rowNumber) throws SQLException {
        return new Row(row.getLong("id"), new EventEnvelope(
                row.getObject("event_id", UUID.class),
                row.getString("event_type"),
                row.getInt("event_version"),
                row.getString("aggregate_type"),
                row.getObject("aggregate_id", UUID.class),
                row.getLong("aggregate_version"),
                row.getObject("partition_key", UUID.class),
                row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                row.getString("producer"),
                row.getString("correlation_id"),
                row.getString("causation_id"),
                row.getString("trace_parent"),
                json.readTree(row.getString("payload"))));
    }

    record Row(long id, EventEnvelope envelope) {
    }
}

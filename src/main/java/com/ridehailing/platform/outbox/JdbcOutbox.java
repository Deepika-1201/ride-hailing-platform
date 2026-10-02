package com.ridehailing.platform.outbox;

import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.LogContext;
import com.ridehailing.platform.Outbox;
import com.ridehailing.shared.Ids;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Fills the envelope from the logging context (LLD §5.2) and inserts it in the caller's transaction. */
@Component
class JdbcOutbox implements Outbox {

    private static final String BASE_PACKAGE = "com.ridehailing.";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    JdbcOutbox(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(DomainEvent event) {
        String role = MDC.get(LogContext.ROLE);
        if (role == null) {
            throw new IllegalStateException("No role in the logging context: every entry point sets one");
        }
        String correlationId = MDC.get(LogContext.CORRELATION_ID);
        String causationId = MDC.get(LogContext.CAUSATION_ID);
        jdbc.sql("""
                        INSERT INTO platform.outbox (event_id, event_type, event_version, aggregate_type, aggregate_id,
                                                     aggregate_version, partition_key, occurred_at, producer,
                                                     correlation_id, causation_id, payload)
                        VALUES (:eventId, :eventType, :eventVersion, :aggregateType, :aggregateId,
                                :aggregateVersion, :partitionKey, :occurredAt, :producer,
                                :correlationId, :causationId, CAST(:payload AS jsonb))
                        """)
                .param("eventId", Ids.newId())
                .param("eventType", event.type())
                .param("eventVersion", event.version())
                .param("aggregateType", event.aggregateType())
                .param("aggregateId", event.aggregateId())
                .param("aggregateVersion", event.aggregateVersion())
                .param("partitionKey", event.partitionKey())
                .param("occurredAt", OffsetDateTime.now(clock))
                .param("producer", moduleOf(event.payload()) + "/" + role)
                .param("correlationId", correlationId != null ? correlationId : event.partitionKey().toString())
                .param("causationId", causationId != null ? causationId : MDC.get(LogContext.REQUEST_ID))
                .param("payload", json.writeValueAsString(event.payload()))
                .update();
    }

    /** The module that owns the payload's package: {@code com.ridehailing.ride.events.X} is from {@code ride}. */
    static String moduleOf(Object payload) {
        String packageName = payload.getClass().getPackageName();
        if (!packageName.startsWith(BASE_PACKAGE)) {
            throw new IllegalArgumentException("Event payloads are records in a module's package, not "
                    + payload.getClass().getName());
        }
        String rest = packageName.substring(BASE_PACKAGE.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }
}

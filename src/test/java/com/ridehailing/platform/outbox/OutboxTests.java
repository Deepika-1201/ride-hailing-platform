package com.ridehailing.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.LogContext;
import com.ridehailing.platform.Outbox;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.PlatformTables;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** LLD §5.2: an event exists if and only if its transaction commits, with its envelope filled from the context. */
class OutboxTests extends IntegrationTest {

    private static final UUID RIDE = UUID.fromString("0199a3f0-7c2e-7a41-9b3d-5f2e8c1d4a10");

    @Autowired
    private Outbox outbox;

    @Autowired
    private TransactionTemplate template;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

    @BeforeEach
    void reset() {
        PlatformTables.reset(jdbc);
    }

    @Test
    void appendingNeedsATransaction() {
        assertThatThrownBy(() -> asApi(() -> outbox.append(event())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aRolledBackTransactionLeavesNoEvent() {
        asApi(() -> template.executeWithoutResult(status -> {
            outbox.append(event());
            status.setRollbackOnly();
        }));

        assertThat(jdbc.sql("SELECT count(*) FROM platform.outbox").query(Long.class).single()).isZero();
    }

    @Test
    void theEnvelopeComesFromTheEventAndTheLoggingContext() {
        LogContext.run(Map.of(LogContext.ROLE, "api", LogContext.REQUEST_ID, "req_7f3c2a91"),
                () -> template.executeWithoutResult(status -> outbox.append(event())));

        Map<String, Object> row = jdbc.sql("SELECT * FROM platform.outbox").query().singleRow();
        assertThat(row).containsEntry("event_type", "TestHappened")
                .containsEntry("event_version", 1)
                .containsEntry("aggregate_type", "ride")
                .containsEntry("aggregate_id", RIDE)
                .containsEntry("aggregate_version", 3L)
                .containsEntry("partition_key", RIDE)
                .containsEntry("producer", "platform/api")
                .containsEntry("correlation_id", RIDE.toString())
                .containsEntry("causation_id", "req_7f3c2a91")
                .containsEntry("published_at", null);
        assertThat(((UUID) row.get("event_id")).version()).isEqualTo(7);
        JsonNode payload = json.readTree(jdbc.sql("SELECT payload::text FROM platform.outbox").query(String.class).single());
        assertThat(payload.get("ride_id").asString()).isEqualTo(RIDE.toString());
        assertThat(payload.get("pickup_note").asString()).isEqualTo("gate 2");
    }

    @Test
    void theContextsCorrelationAndCausationWin() {
        LogContext.run(Map.of(LogContext.ROLE, "worker", LogContext.CORRELATION_ID, "ride-flow-1",
                        LogContext.CAUSATION_ID, "0199a3f0-7d01-7e02-8f03-a1b2c3d4e5f6"),
                () -> template.executeWithoutResult(status -> outbox.append(event())));

        Map<String, Object> row = jdbc.sql("SELECT * FROM platform.outbox").query().singleRow();
        assertThat(row).containsEntry("producer", "platform/worker")
                .containsEntry("correlation_id", "ride-flow-1")
                .containsEntry("causation_id", "0199a3f0-7d01-7e02-8f03-a1b2c3d4e5f6");
    }

    @Test
    void anEntryPointThatSetNoRoleIsAProgrammingError() {
        assertThatThrownBy(() -> template.executeWithoutResult(status -> outbox.append(event())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("role");
    }

    @Test
    void payloadsAreRecordsInAModulesPackage() {
        assertThatThrownBy(() -> asApi(() -> template.executeWithoutResult(status ->
                outbox.append(DomainEvent.of("TestHappened", 1, "ride", RIDE, 1, Map.of("ride_id", RIDE))))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventTypesArePascalCaseNames() {
        assertThatThrownBy(() -> DomainEvent.of("ride.requested", 1, "ride", RIDE, 1, new Happened(RIDE, "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DomainEvent event() {
        return DomainEvent.of("TestHappened", 1, "ride", RIDE, 3, new Happened(RIDE, "gate 2"));
    }

    private static void asApi(Runnable work) {
        LogContext.run(Map.of(LogContext.ROLE, "api"), work);
    }

    record Happened(UUID rideId, String pickupNote) {
    }
}

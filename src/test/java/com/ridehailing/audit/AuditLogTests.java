package com.ridehailing.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.platform.LogContext;
import com.ridehailing.shared.Actor;
import com.ridehailing.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** LLD §4.2, §5.6: entries join the caller's transaction, carry the context's IDs, and can never be changed. */
class AuditLogTests extends IntegrationTest {

    private static final AuditEntry CANCELLATION = new AuditEntry(new Actor(Actor.Type.OPS, "ops-7"), "ride.cancel",
            "ride", "0199a3f0-7c2e-7a41-9b3d-5f2e8c1d4a10", "rider asked by phone",
            Map.of("status", "ASSIGNED"), Map.of("status", "CANCELLED"));

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private TransactionTemplate template;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        // TRUNCATE fires no row trigger; it is how tests, and only tests, empty the log.
        jdbc.sql("TRUNCATE audit.audit_log").update();
    }

    @Test
    void recordingNeedsATransaction() {
        assertThatThrownBy(() -> auditLog.record(CANCELLATION)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aRolledBackTransactionLeavesNoEntry() {
        template.executeWithoutResult(status -> {
            auditLog.record(CANCELLATION);
            status.setRollbackOnly();
        });

        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_log").query(Long.class).single()).isZero();
    }

    @Test
    void recordsTheEntryWithTheContextsRequestAndCorrelationIds() {
        LogContext.run(Map.of(LogContext.REQUEST_ID, "req_42", LogContext.CORRELATION_ID, CANCELLATION.entityId()),
                () -> template.executeWithoutResult(status -> auditLog.record(CANCELLATION)));

        Map<String, Object> row = jdbc.sql("""
                        SELECT id, occurred_at, actor_type, actor_id, action, entity_type, entity_id, reason, request_id,
                               correlation_id, before_state ->> 'status' AS before, after_state ->> 'status' AS after,
                               tableoid::regclass::text AS partition
                        FROM audit.audit_log
                        """)
                .query().singleRow();
        assertThat(row).containsEntry("actor_type", "OPS")
                .containsEntry("actor_id", "ops-7")
                .containsEntry("action", "ride.cancel")
                .containsEntry("entity_type", "ride")
                .containsEntry("entity_id", CANCELLATION.entityId())
                .containsEntry("reason", "rider asked by phone")
                .containsEntry("request_id", "req_42")
                .containsEntry("correlation_id", CANCELLATION.entityId())
                .containsEntry("before", "ASSIGNED")
                .containsEntry("after", "CANCELLED");
        assertThat(((UUID) row.get("id")).version()).isEqualTo(7);
        YearMonth month = YearMonth.from(((Timestamp) row.get("occurred_at")).toInstant().atOffset(ZoneOffset.UTC));
        assertThat(month).isEqualTo(YearMonth.from(OffsetDateTime.now(ZoneOffset.UTC)));
        assertThat((String) row.get("partition")).isEqualTo("audit.audit_log_%04d_%02d"
                .formatted(month.getYear(), month.getMonthValue()));
    }

    @Test
    void entriesCannotBeUpdated() {
        template.executeWithoutResult(status -> auditLog.record(CANCELLATION));

        assertThatThrownBy(() -> jdbc.sql("UPDATE audit.audit_log SET reason = 'edited'").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_log is append-only: UPDATE is not allowed");
    }

    @Test
    void entriesCannotBeDeleted() {
        template.executeWithoutResult(status -> auditLog.record(CANCELLATION));

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit.audit_log").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_log is append-only: DELETE is not allowed");
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_log").query(Long.class).single()).isEqualTo(1);
    }
}

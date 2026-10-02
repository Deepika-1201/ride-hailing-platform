package com.ridehailing.platform.timers;

import com.ridehailing.platform.DueTimer;
import com.ridehailing.platform.Role;
import com.ridehailing.platform.RoleComponent;
import com.ridehailing.platform.TimerHandler;
import com.ridehailing.platform.workers.BackgroundWorker;
import com.ridehailing.platform.workers.WorkerProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fires due timers, one per transaction, on a few threads per node (LLD §5.4). It claims only kinds with a handler in
 * this process, so an old node in a rolling deployment never takes, and parks, a kind only the new version knows.
 */
@RoleComponent(Role.DISPATCH)
class TimerPoller extends BackgroundWorker {

    private static final Logger log = LoggerFactory.getLogger(TimerPoller.class);
    private static final int MAX_ERROR_LENGTH = 2_000;

    private final Map<String, TimerHandler> handlers = new HashMap<>();
    private final List<String> kinds;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final TimerProperties properties;

    TimerPoller(ObjectProvider<TimerHandler> handlers, JdbcClient jdbc, TransactionTemplate transactions,
            JsonMapper json, TimerProperties properties, WorkerProperties workers) {
        super("timer-poller", Role.DISPATCH, properties.workers(), properties.pollInterval(), workers);
        handlers.forEach(handler -> {
            if (this.handlers.putIfAbsent(handler.kind().name(), handler) != null) {
                throw new IllegalStateException("Two timer handlers for " + handler.kind().name());
            }
        });
        this.kinds = List.copyOf(this.handlers.keySet());
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.properties = properties;
    }

    @Override
    protected boolean work() {
        return fireNext();
    }

    /** Fires the most overdue timer of a kind handled here; returns whether one was due. */
    boolean fireNext() {
        if (kinds.isEmpty()) {
            return false;
        }
        AtomicReference<DueTimer> claimed = new AtomicReference<>();
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> {
                Optional<DueTimer> due = claim();
                if (due.isEmpty()) {
                    return false;
                }
                DueTimer timer = due.get();
                claimed.set(timer);
                handlers.get(timer.kind()).fire(timer);
                jdbc.sql("DELETE FROM platform.timers WHERE id = :id").param("id", timer.id()).update();
                return true;
            }));
        } catch (RuntimeException e) {
            DueTimer timer = claimed.get();
            if (timer == null) {
                throw e;
            }
            recordFailure(timer, e);
            return true;
        }
    }

    private Optional<DueTimer> claim() {
        return jdbc.sql("""
                        SELECT id, kind, aggregate_id, payload::text, due_at, attempts FROM platform.timers
                        WHERE due_at <= clock_timestamp() AND parked_at IS NULL AND kind IN (:kinds)
                        ORDER BY due_at LIMIT 1
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("kinds", kinds)
                .query(this::timer)
                .optional();
    }

    /** After the rollback: retry in min(2^attempts, 60) s, or park the timer after too many failures. */
    private void recordFailure(DueTimer timer, RuntimeException failure) {
        boolean parked = jdbc.sql("""
                        UPDATE platform.timers
                        SET attempts = attempts + 1, last_error = :error,
                            due_at = now() + make_interval(secs => least(power(2, attempts + 1), 60)),
                            parked_at = CASE WHEN attempts + 1 >= :maxFailures THEN now() END
                        WHERE id = :id
                        RETURNING parked_at IS NOT NULL
                        """)
                .param("error", describe(failure))
                .param("maxFailures", properties.maxFailures())
                .param("id", timer.id())
                .query(Boolean.class)
                .optional()
                .orElse(false);
        if (parked) {
            log.error("Parked timer {} ({}) after {} failures", timer.id(), timer.kind(), timer.attempts() + 1, failure);
        } else {
            log.warn("Timer {} ({}) failed; it will be retried", timer.id(), timer.kind(), failure);
        }
    }

    private DueTimer timer(ResultSet row, int rowNumber) throws SQLException {
        return new DueTimer(
                row.getObject("id", UUID.class),
                row.getString("kind"),
                row.getObject("aggregate_id", UUID.class),
                json.readTree(row.getString("payload")),
                row.getObject("due_at", OffsetDateTime.class).toInstant(),
                row.getInt("attempts"));
    }

    private static String describe(RuntimeException failure) {
        String text = failure.getClass().getName() + ": " + failure.getMessage();
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}

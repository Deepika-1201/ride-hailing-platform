package com.ridehailing.platform.outbox;

import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.EventEnvelope;
import com.ridehailing.platform.LogContext;
import com.ridehailing.platform.RecurringJob;
import com.ridehailing.platform.Role;
import com.ridehailing.platform.Transactions;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class EventReplayJob implements RecurringJob {

    static final int BATCH_SIZE = 50;

    private static final Logger log = LoggerFactory.getLogger(EventReplayJob.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final EventConsumers consumers;
    private final ConsumerDelivery delivery;
    private final Transactions transactions;
    private final Counter handled;
    private final Counter skipped;
    private final Counter completed;
    private final Counter failures;

    EventReplayJob(JdbcClient jdbc, JsonMapper json, EventConsumers consumers, ConsumerDelivery delivery,
            Transactions transactions, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.json = json;
        this.consumers = consumers;
        this.delivery = delivery;
        this.transactions = transactions;
        handled = metrics.counter("event_replay.events", "outcome", "handled");
        skipped = metrics.counter("event_replay.events", "outcome", "deduplicated");
        completed = metrics.counter("event_replays.completed");
        failures = metrics.counter("event_replays.failures");
        Gauge.builder("event_replays.running", jdbc, database -> database.sql(
                "SELECT count(*) FROM platform.event_replays WHERE status = 'RUNNING'").query(Long.class).single()).register(metrics);
    }

    @Override
    public String name() {
        return "event-replay";
    }

    @Override
    public Role role() {
        return Role.WORKER;
    }

    @Override
    public Duration interval() {
        return Duration.ofSeconds(1);
    }

    @Override
    public void run() {
        processNext();
    }

    boolean processNext() {
        AtomicReference<Work> attempted = new AtomicReference<>();
        try {
            return transactions.execute(() -> {
                Work work = jdbc.sql("""
                        SELECT id, consumer, reprocess, events, total_events, generation
                        FROM platform.event_replays WHERE status = 'RUNNING'
                        ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                        """)
                        .query((row, rowNumber) -> new Work(row.getObject("id", UUID.class), row.getString("consumer"),
                                row.getBoolean("reprocess"), row.getLong("events"), row.getLong("total_events"), row.getLong("generation")))
                        .optional().orElse(null);
                if (work == null) {
                    return false;
                }
                attempted.set(work);
                EventConsumer consumer = consumers.named(work.consumer()).orElseThrow(() ->
                        new IllegalStateException("The replay consumer is no longer registered"));
                List<Item> items = jdbc.sql("""
                        SELECT outbox_id, envelope::text FROM platform.event_replay_items
                        WHERE replay_id = :id ORDER BY outbox_id LIMIT :limit
                        """)
                        .param("id", work.id()).param("limit", BATCH_SIZE)
                        .query((row, rowNumber) -> new Item(row.getLong("outbox_id"), json.readValue(row.getString("envelope"), EventEnvelope.class)))
                        .list();
                if (items.isEmpty() && work.events() != work.total()) {
                    throw new IllegalStateException("Replay snapshot is missing pending events");
                }
                for (Item item : items) {
                    EventEnvelope event = item.event();
                    if (!consumer.eventTypes().contains(event.eventType()) || event.eventVersion() != 1) {
                        throw new IllegalStateException("The replay consumer no longer supports a selected event");
                    }
                    if (work.reprocess()) {
                        jdbc.sql("DELETE FROM platform.inbox WHERE consumer = :consumer AND event_id = :event")
                                .param("consumer", consumer.name()).param("event", event.eventId()).update();
                    }
                    LogContext.run(Map.of(LogContext.ROLE, "worker"), () -> {
                        Counter counter = delivery.deliver(consumer, event) ? handled : skipped;
                        transactions.afterCommit(counter::increment);
                    });
                }
                if (!items.isEmpty()) {
                    jdbc.sql("DELETE FROM platform.event_replay_items WHERE replay_id = :id AND outbox_id IN (:items)")
                            .param("id", work.id()).param("items", items.stream().map(Item::outboxId).toList()).update();
                }
                long progress = work.events() + items.size();
                boolean done = progress == work.total();
                jdbc.sql("UPDATE platform.event_replays SET events = :events, status = :status, updated_at = now() WHERE id = :id")
                        .param("events", progress).param("status", done ? "DONE" : "RUNNING").param("id", work.id()).update();
                if (done) {
                    transactions.afterCommit(completed::increment);
                }
                return true;
            });
        } catch (DataAccessException unavailable) {
            throw unavailable;
        } catch (RuntimeException failed) {
            Work work = attempted.get();
            if (work == null) {
                throw failed;
            }
            String error = (failed.getClass().getSimpleName() + ": " + failed.getMessage()).replace("\u0000", "\\u0000");
            int marked = jdbc.sql("""
                    UPDATE platform.event_replays SET status = 'FAILED', error = :error, updated_at = now()
                    WHERE id = :id AND status = 'RUNNING' AND events = :events AND generation = :generation
                    """)
                    .param("id", work.id()).param("events", work.events()).param("generation", work.generation())
                    .param("error", error.substring(0, Math.min(error.length(), 2_000))).update();
            if (marked != 0) {
                failures.increment();
                log.warn("Replay {} failed after {} committed events", work.id(), work.events(), failed);
            }
            return true;
        }
    }

    private record Work(UUID id, String consumer, boolean reprocess, long events, long total, long generation) {
    }

    private record Item(long outboxId, EventEnvelope event) {
    }
}
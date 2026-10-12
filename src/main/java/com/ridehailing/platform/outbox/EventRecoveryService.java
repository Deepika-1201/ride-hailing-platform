package com.ridehailing.platform.outbox;

import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Cursor;
import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.EventRecovery;
import com.ridehailing.platform.Transactions;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.Page;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
class EventRecoveryService implements EventRecovery {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final EventConsumers consumers;
    private final ConsumerDelivery delivery;
    private final Transactions transactions;
    private final Counter redriven;
    private final Counter replayStarted;
    private final Counter replayRetried;

    EventRecoveryService(JdbcClient jdbc, JsonMapper json, EventConsumers consumers, ConsumerDelivery delivery,
            Transactions transactions, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.json = json;
        this.consumers = consumers;
        this.delivery = delivery;
        this.transactions = transactions;
        redriven = metrics.counter("dead_letters.redriven");
        replayStarted = metrics.counter("event_replays.started");
        replayRetried = metrics.counter("event_replays.retries");
        Gauge.builder("dead_letters.pending", jdbc, database -> database.sql(
                "SELECT count(*) FROM platform.dead_letters WHERE resolved_at IS NULL").query(Long.class).single())
                .register(metrics);
    }

    @Override
    public Page<DeadLetterView> deadLetters(String consumer, Cursor cursor, int limit) {
        int pageSize = Cursor.limit(limit);
        String filter = " WHERE true" + (consumer == null ? "" : " AND consumer = :consumer")
                + (cursor == null ? "" : " AND (created_at, id) < (:created, :id)");
        var query = jdbc.sql("SELECT * FROM platform.dead_letters" + filter + " ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("limit", pageSize + 1);
        if (consumer != null) {
            query.param("consumer", consumer);
        }
        if (cursor != null) {
            query.param("created", cursor.createdAt().atOffset(ZoneOffset.UTC)).param("id", cursor.id());
        }
        List<DeadLetterView> found = query.query(this::letter).list().stream().map(StoredLetter::view).toList();
        List<DeadLetterView> page = found.stream().limit(pageSize).toList();
        String next = found.size() > pageSize ? new Cursor(page.getLast().createdAt(), page.getLast().id()).encode() : null;
        return new Page<>(page, next);
    }

    @Override
    public DeadLetterView redrive(UUID id) {
        return transactions.execute(() -> {
            StoredLetter letter = jdbc.sql("SELECT * FROM platform.dead_letters WHERE id = :id FOR UPDATE")
                    .param("id", id).query(this::letter).optional().orElseThrow(ApiException::notFound);
            DeadLetterView view = letter.view();
            if (view.redrivenAt() != null) {
                return view;
            }
            EventConsumer consumer = consumers.named(view.consumer()).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                    "CONSUMER_UNAVAILABLE", "The original event consumer is not registered in this process."));
            String suffix = ".dlq." + consumer.name();
            if (!view.topic().endsWith(suffix)) {
                throw invalidLetter();
            }
            String topic = view.topic().substring(0, view.topic().length() - suffix.length());
            ConsumerRecord<String, byte[]> record = new ConsumerRecord<>(topic, letter.partition(), letter.offset(),
                    letter.key() == null ? null : new String(letter.key(), StandardCharsets.UTF_8), letter.value());
            letter.headers().forEach(header -> record.headers().add(header.key(), header.value()));
            boolean subscribed;
            try {
                subscribed = new KafkaEventListener(consumer, delivery, json).deliverRecord(record);
            } catch (SerializationException invalid) {
                throw invalidLetter();
            } catch (DataAccessException unavailable) {
                throw unavailable;
            } catch (RuntimeException failed) {
                throw new ApiException(HttpStatus.CONFLICT, "DEAD_LETTER_REDRIVE_FAILED",
                        "The consumer still rejects this event; the dead letter remains unresolved.");
            }
            if (!subscribed) {
                throw new ApiException(HttpStatus.CONFLICT, "CONSUMER_NOT_SUBSCRIBED",
                        "The original consumer no longer subscribes to this event type.");
            }
            Instant resolved = jdbc.sql("UPDATE platform.dead_letters SET resolved_at = now() WHERE id = :id RETURNING resolved_at")
                    .param("id", id).query(OffsetDateTime.class).single().toInstant();
            transactions.afterCommit(redriven::increment);
            return new DeadLetterView(view.id(), view.consumer(), view.topic(), view.eventId(), view.eventType(), view.error(),
                    view.createdAt(), resolved);
        });
    }

        @Override
        public ReplayView startReplay(String consumer, Instant from, Instant to, boolean reprocess) {
        EventConsumer target = consumers.named(consumer).orElseThrow(() -> ApiException.invalid("consumer", "must name a registered consumer"));
        if (from == null || to == null || !from.truncatedTo(ChronoUnit.MICROS).isBefore(to.truncatedTo(ChronoUnit.MICROS))) {
            throw ApiException.invalid("to", "must be after from at microsecond precision");
        }
        return transactions.execute(() -> {
            UUID id = Ids.newId();
            jdbc.sql("""
                INSERT INTO platform.event_replays (id, consumer, from_at, to_at, reprocess)
                VALUES (:id, :consumer, :from, :to, :reprocess)
                """)
                .param("id", id).param("consumer", target.name()).param("from", from.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                .param("to", to.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC)).param("reprocess", reprocess).update();
            int selected = jdbc.sql("""
                INSERT INTO platform.event_replay_items (replay_id, outbox_id, envelope)
                SELECT :id, source.id, to_jsonb(source) - 'id' - 'published_at'
                FROM platform.outbox source
                WHERE source.occurred_at >= :from AND source.occurred_at < :to
                  AND source.event_type = ANY(:types)
                """)
                .param("id", id).param("from", from.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                .param("to", to.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                .param("types", target.eventTypes().toArray(String[]::new)).update();
            jdbc.sql("UPDATE platform.event_replays SET total_events = :total WHERE id = :id")
                .param("total", selected).param("id", id).update();
            transactions.afterCommit(replayStarted::increment);
            return replay(id);
        });
        }

        @Override
        public ReplayView replay(UUID id) {
        return jdbc.sql("SELECT * FROM platform.event_replays WHERE id = :id").param("id", id)
            .query((row, rowNumber) -> new ReplayView(row.getObject("id", UUID.class), row.getString("consumer"),
                row.getObject("from_at", OffsetDateTime.class).toInstant(), row.getObject("to_at", OffsetDateTime.class).toInstant(),
                row.getString("status"), row.getLong("events"), row.getLong("total_events"), row.getBoolean("reprocess"),
                row.getString("error")))
            .optional().orElseThrow(ApiException::notFound);
        }

        @Override
        public ReplayView retryReplay(UUID id) {
            return transactions.execute(() -> {
                String status = jdbc.sql("SELECT status FROM platform.event_replays WHERE id = :id FOR UPDATE")
                        .param("id", id).query(String.class).optional().orElseThrow(ApiException::notFound);
                if (!status.equals("FAILED")) {
                    throw new ApiException(HttpStatus.CONFLICT, "REPLAY_NOT_FAILED", "Only a failed replay can be retried.");
                }
                jdbc.sql("""
                        UPDATE platform.event_replays SET status = 'RUNNING', error = NULL, generation = generation + 1, updated_at = now()
                        WHERE id = :id
                        """).param("id", id).update();
                transactions.afterCommit(replayRetried::increment);
                return replay(id);
            });
        }

        private StoredLetter letter(ResultSet row, int rowNumber) throws SQLException {
        List<KafkaDeadLetterListener.StoredHeader> headers = json.readValue(row.getBytes("record_headers"), new TypeReference<>() { });
        String eventType = null;
        for (KafkaDeadLetterListener.StoredHeader header : headers) {
            if (header.key().equals("event_type") && header.value() != null) {
                eventType = new String(header.value(), StandardCharsets.UTF_8);
            }
        }
        OffsetDateTime resolved = row.getObject("resolved_at", OffsetDateTime.class);
        DeadLetterView view = new DeadLetterView(row.getObject("id", UUID.class), row.getString("consumer"), row.getString("topic"),
                row.getObject("event_id", UUID.class), eventType, row.getString("error"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(), resolved == null ? null : resolved.toInstant());
        return new StoredLetter(view, row.getInt("kafka_partition"), row.getLong("kafka_offset"), row.getBytes("record_key"),
                row.getBytes("record_value"), headers);
    }

    private static ApiException invalidLetter() {
        return new ApiException(HttpStatus.CONFLICT, "DEAD_LETTER_INVALID",
                "The stored record is not a supported event; it has not been modified or resolved.");
    }

    private record StoredLetter(DeadLetterView view, int partition, long offset, byte[] key, byte[] value,
            List<KafkaDeadLetterListener.StoredHeader> headers) {
    }
}
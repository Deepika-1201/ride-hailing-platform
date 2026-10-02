package com.ridehailing.platform.outbox;

import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.EventEnvelope;
import com.ridehailing.platform.LogContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Delivers an event to one consumer through the inbox, with retries, then sets it aside (LLD §5.3). */
@Component
class ConsumerDelivery {

    private static final Logger log = LoggerFactory.getLogger(ConsumerDelivery.class);
    private static final int MAX_ERROR_LENGTH = 2_000;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final OutboxProperties properties;

    ConsumerDelivery(JdbcClient jdbc, TransactionTemplate transactions, OutboxProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
    }

    /** Handles the event in one transaction with its inbox row; false if the consumer had handled it already. */
    boolean deliver(EventConsumer consumer, EventEnvelope event) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int inserted = jdbc.sql("""
                            INSERT INTO platform.inbox (consumer, event_id) VALUES (:consumer, :eventId)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("consumer", consumer.name())
                    .param("eventId", event.eventId())
                    .update();
            if (inserted == 0) {
                return false;
            }
            LogContext.run(Map.of(
                    LogContext.CORRELATION_ID, event.correlationId(),
                    LogContext.CAUSATION_ID, event.eventId().toString()), () -> consumer.handle(event));
            return true;
        }));
    }

    /** Retries after each configured delay; after the last, sets the event aside for this consumer only. */
    void deliverWithRetries(EventConsumer consumer, EventEnvelope event, long outboxId) {
        List<Duration> delays = properties.retryDelays();
        for (int attempt = 1; ; attempt++) {
            try {
                deliver(consumer, event);
                return;
            } catch (RuntimeException e) {
                if (attempt > delays.size()) {
                    recordFailure(consumer.name(), event.eventId(), outboxId, attempt, e);
                    log.error("Set aside {} {} for consumer {} after {} attempts", event.eventType(), event.eventId(),
                            consumer.name(), attempt, e);
                    return;
                }
                log.warn("Consumer {} failed on {} {} (attempt {}); retrying", consumer.name(), event.eventType(),
                        event.eventId(), attempt, e);
                pause(delays.get(attempt - 1));
            }
        }
    }

    /** Adds {@code attempts} to the consumer's failed delivery of the event, creating it or reopening it. */
    void recordFailure(String consumer, UUID eventId, long outboxId, int attempts, RuntimeException failure) {
        jdbc.sql("""
                        INSERT INTO platform.failed_deliveries AS failed
                            (consumer, event_id, outbox_id, attempts, last_error, failed_at)
                        VALUES (:consumer, :eventId, :outboxId, :attempts, :error, now())
                        ON CONFLICT (consumer, event_id) DO UPDATE
                        SET attempts = failed.attempts + excluded.attempts, last_error = excluded.last_error,
                            failed_at = excluded.failed_at, redriven_at = NULL
                        """)
                .param("consumer", consumer)
                .param("eventId", eventId)
                .param("outboxId", outboxId)
                .param("attempts", attempts)
                .param("error", describe(failure))
                .update();
    }

    private static String describe(RuntimeException failure) {
        String text = failure.getClass().getName() + ": " + failure.getMessage();
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }

    private static void pause(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted between delivery attempts", e);
        }
    }
}

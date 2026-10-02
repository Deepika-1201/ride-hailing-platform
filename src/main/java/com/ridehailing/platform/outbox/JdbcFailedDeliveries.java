package com.ridehailing.platform.outbox;

import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.FailedDeliveries;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcFailedDeliveries implements FailedDeliveries {

    private static final Logger log = LoggerFactory.getLogger(JdbcFailedDeliveries.class);

    private final JdbcClient jdbc;
    private final OutboxRows rows;
    private final EventConsumers consumers;
    private final ConsumerDelivery delivery;

    JdbcFailedDeliveries(JdbcClient jdbc, OutboxRows rows, EventConsumers consumers, ConsumerDelivery delivery) {
        this.jdbc = jdbc;
        this.rows = rows;
        this.consumers = consumers;
        this.delivery = delivery;
    }

    @Override
    public boolean redrive(String consumerName, UUID eventId) {
        long unresolved = jdbc.sql("""
                        SELECT count(*) FROM platform.failed_deliveries
                        WHERE consumer = :consumer AND event_id = :eventId AND redriven_at IS NULL
                        """)
                .param("consumer", consumerName)
                .param("eventId", eventId)
                .query(Long.class)
                .single();
        if (unresolved == 0) {
            throw new NoSuchElementException("No unresolved failed delivery of " + eventId + " to " + consumerName);
        }
        EventConsumer consumer = consumers.named(consumerName)
                .orElseThrow(() -> new NoSuchElementException("No consumer named " + consumerName + " here"));
        OutboxRows.Row row = rows.byEventId(eventId)
                .orElseThrow(() -> new NoSuchElementException("Event " + eventId + " is no longer in the outbox"));
        try {
            delivery.deliver(consumer, row.envelope());
        } catch (RuntimeException e) {
            delivery.recordFailure(consumerName, eventId, row.id(), 1, e);
            log.warn("Re-drive of {} to {} failed", eventId, consumerName, e);
            return false;
        }
        jdbc.sql("""
                        UPDATE platform.failed_deliveries SET redriven_at = now()
                        WHERE consumer = :consumer AND event_id = :eventId
                        """)
                .param("consumer", consumerName)
                .param("eventId", eventId)
                .update();
        log.info("Re-drove {} to {}", eventId, consumerName);
        return true;
    }
}

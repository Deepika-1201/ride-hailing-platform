package com.ridehailing.platform.outbox;

import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.EventEnvelope;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers chosen events to the consumers, as the relay would, while the relay is stopped: a test delivers its own
 * ride's events and no one else's. {@link #replay} hands a consumer an event again past its inbox, as a replay that
 * reaches a consumer under a new event ID would.
 */
@TestComponent
public class EventDelivery {

    private final JdbcClient jdbc;
    private final OutboxRows rows;
    private final EventConsumers consumers;
    private final ConsumerDelivery delivery;
    private final TransactionTemplate transactions;

    EventDelivery(JdbcClient jdbc, OutboxRows rows, EventConsumers consumers, ConsumerDelivery delivery,
            TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.rows = rows;
        this.consumers = consumers;
        this.delivery = delivery;
        this.transactions = transactions;
    }

    /** Every event with this partition key (a ride's) to each subscribed consumer, in outbox order. */
    public void deliverAll(UUID partitionKey) {
        List<UUID> eventIds = jdbc.sql("SELECT event_id FROM platform.outbox WHERE partition_key = :key ORDER BY id")
                .param("key", partitionKey)
                .query(UUID.class)
                .list();
        for (UUID eventId : eventIds) {
            EventEnvelope event = rows.byEventId(eventId).orElseThrow().envelope();
            consumers.subscribedTo(event.eventType()).forEach(consumer -> delivery.deliver(consumer, event));
        }
    }

    /** The event to one consumer through its inbox; false if the consumer had handled it already. */
    public boolean deliver(String consumer, UUID eventId) {
        return delivery.deliver(consumers.named(consumer).orElseThrow(), rows.byEventId(eventId).orElseThrow()
                .envelope());
    }

    /** The event to one consumer in a transaction of its own, past the inbox. */
    public void replay(String consumer, UUID eventId) {
        EventConsumer target = consumers.named(consumer).orElseThrow();
        EventEnvelope event = rows.byEventId(eventId).orElseThrow().envelope();
        transactions.executeWithoutResult(_ -> target.handle(event));
    }
}

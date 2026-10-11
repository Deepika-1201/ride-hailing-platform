package com.ridehailing.platform.outbox;

import com.ridehailing.platform.EventConsumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ride.kafka", name = "enabled", havingValue = "false", matchIfMissing = true)
class InProcessOutboxTransport implements OutboxTransport {

    private final EventConsumers consumers;
    private final ConsumerDelivery delivery;

    InProcessOutboxTransport(EventConsumers consumers, ConsumerDelivery delivery) {
        this.consumers = consumers;
        this.delivery = delivery;
    }

    @Override
    public void publish(OutboxRows.Row row) {
        for (EventConsumer consumer : consumers.subscribedTo(row.envelope().eventType())) {
            delivery.deliverWithRetries(consumer, row.envelope(), row.id());
        }
    }
}
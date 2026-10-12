package com.ridehailing.platform.outbox;

import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.EventEnvelope;
import com.ridehailing.platform.LogContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.json.JsonMapper;

class KafkaEventListener implements AcknowledgingMessageListener<String, byte[]> {

    private final EventConsumer consumer;
    private final ConsumerDelivery delivery;
    private final ObjectReader reader;

    KafkaEventListener(EventConsumer consumer, ConsumerDelivery delivery, JsonMapper json) {
        this.consumer = Objects.requireNonNull(consumer);
        this.delivery = Objects.requireNonNull(delivery);
        this.reader = json.readerFor(WireEvent.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    @Override
    public void onMessage(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        Objects.requireNonNull(acknowledgment, "Manual Kafka acknowledgement is required");
        deliverRecord(record);
        acknowledgment.acknowledge();
    }

    boolean deliverRecord(ConsumerRecord<String, byte[]> record) {
        EventEnvelope event = decode(record);
        if (consumer.eventTypes().contains(event.eventType())) {
            if (event.eventVersion() != 1) {
                throw new SerializationException("Unsupported " + event.eventType() + " version " + event.eventVersion());
            }
            LogContext.run(Map.of(LogContext.ROLE, "worker"), () -> delivery.deliver(consumer, event));
            return true;
        }
        return false;
    }

    private EventEnvelope decode(ConsumerRecord<String, byte[]> record) {
        try {
            WireEvent value = reader.readValue(record.value());
            UUID partitionKey = UUID.fromString(record.key());
            if (!partitionKey.toString().equals(record.key())) {
                throw new IllegalArgumentException("Kafka key must be a canonical UUID");
            }
            EventEnvelope event = value.envelope(partitionKey);
            if (!record.topic().equals(KafkaTopics.forEvent(event.eventType()))) {
                throw new IllegalArgumentException("Event type does not belong to its Kafka topic");
            }
            requireHeader(record, "event_id", event.eventId().toString());
            requireHeader(record, "event_type", event.eventType());
            requireHeader(record, "event_version", Integer.toString(event.eventVersion()));
            requireHeader(record, "traceparent", event.traceParent());
            return event;
        } catch (RuntimeException failure) {
            throw new SerializationException("Invalid Kafka event at " + record.topic() + "-" + record.partition()
                    + " offset " + record.offset(), failure);
        }
    }

    private static void requireHeader(ConsumerRecord<String, byte[]> record, String name, String expected) {
        var headers = record.headers().headers(name).iterator();
        if (expected == null) {
            if (headers.hasNext()) {
                throw new IllegalArgumentException("Unexpected Kafka header " + name);
            }
            return;
        }
        if (!headers.hasNext()) {
            throw new IllegalArgumentException("Missing Kafka header " + name);
        }
        Header header = headers.next();
        if (headers.hasNext() || !Arrays.equals(header.value(), expected.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Conflicting Kafka header " + name);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record WireEvent(UUID eventId, String eventType, int eventVersion, String aggregateType, UUID aggregateId,
            Long aggregateVersion, Instant occurredAt, String producer, String correlationId, String causationId,
            String traceParent, JsonNode payload) {

        WireEvent {
            Objects.requireNonNull(eventId, "event_id");
            Objects.requireNonNull(aggregateId, "aggregate_id");
                Objects.requireNonNull(aggregateVersion, "aggregate_version");
            Objects.requireNonNull(occurredAt, "occurred_at");
            if (List.of(eventType, aggregateType, producer, correlationId).stream().anyMatch(String::isBlank)
                    || eventVersion < 1 || aggregateVersion < 0 || payload == null || !payload.isObject()
                    || causationId != null && causationId.isBlank() || traceParent != null && traceParent.isBlank()) {
                throw new IllegalArgumentException("Invalid event envelope fields");
            }
        }

        EventEnvelope envelope(UUID partitionKey) {
            return new EventEnvelope(eventId, eventType, eventVersion, aggregateType, aggregateId, aggregateVersion,
                    partitionKey, occurredAt, producer, correlationId, causationId, traceParent, payload);
        }
    }
}
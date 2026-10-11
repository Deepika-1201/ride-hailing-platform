package com.ridehailing.platform.outbox;

import com.ridehailing.platform.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class KafkaOutboxTransport implements OutboxTransport, AutoCloseable {

    private final JsonMapper json;
    private final Supplier<Producer<String, byte[]>> producerFactory;
    private final Duration timeout;
    private Producer<String, byte[]> producer;
    private boolean closed;

    KafkaOutboxTransport(JsonMapper json, List<String> brokers, Duration timeout) {
        this(json, () -> new KafkaProducer<>(producerProperties(brokers, timeout)), timeout);
    }

    KafkaOutboxTransport(JsonMapper json, Supplier<Producer<String, byte[]>> producerFactory, Duration timeout) {
        this.json = Objects.requireNonNull(json);
        this.producerFactory = Objects.requireNonNull(producerFactory);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Kafka acknowledgement timeout must be positive");
        }
    }

    static Map<String, Object> producerProperties(List<String> brokers, Duration timeout) {
        if (brokers.isEmpty() || brokers.stream().anyMatch(String::isBlank) || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Kafka brokers and a positive acknowledgement timeout are required");
        }
        return Map.ofEntries(
                Map.entry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, List.copyOf(brokers)),
                Map.entry(ProducerConfig.CLIENT_ID_CONFIG, "ride-outbox"),
                Map.entry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class),
                Map.entry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class),
                Map.entry(ProducerConfig.ACKS_CONFIG, "all"),
                Map.entry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true),
                Map.entry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5),
                Map.entry(ProducerConfig.LINGER_MS_CONFIG, 5L),
                Map.entry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd"),
                Map.entry(ProducerConfig.MAX_BLOCK_MS_CONFIG, Math.max(1L, timeout.toMillis())));
    }

    @Override
    public synchronized void publish(OutboxRows.Row row) {
        if (closed) {
            throw new IllegalStateException("Kafka outbox transport is closed");
        }
        ProducerRecord<String, byte[]> record = record(row.envelope());
        long started = System.nanoTime();
        try {
            if (producer == null) {
                producer = Objects.requireNonNull(producerFactory.get());
            }
            var acknowledgement = producer.send(record);
            long remaining = Math.max(0L, timeout.toNanos() - (System.nanoTime() - started));
            acknowledgement.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            IllegalStateException failed = publicationFailed(row.envelope(), interrupted);
            Thread.currentThread().interrupt();
            throw failed;
        } catch (ExecutionException failed) {
            throw publicationFailed(row.envelope(), failed.getCause());
        } catch (TimeoutException | RuntimeException failed) {
            throw publicationFailed(row.envelope(), failed);
        }
    }

    private ProducerRecord<String, byte[]> record(EventEnvelope event) {
        String topic = KafkaTopics.forEvent(event.eventType());
        ObjectNode value = json.createObjectNode();
        value.put("event_id", event.eventId().toString());
        value.put("event_type", event.eventType());
        value.put("event_version", event.eventVersion());
        value.put("aggregate_type", event.aggregateType());
        value.put("aggregate_id", event.aggregateId().toString());
        value.put("aggregate_version", event.aggregateVersion());
        value.put("occurred_at", event.occurredAt().toString());
        value.put("producer", event.producer());
        value.put("correlation_id", event.correlationId());
        if (event.causationId() != null && !event.causationId().isBlank()) {
            value.put("causation_id", event.causationId());
        }
        if (event.traceParent() != null && !event.traceParent().isBlank()) {
            value.put("trace_parent", event.traceParent());
        }
        value.set("payload", event.payload());
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, event.partitionKey().toString(),
                json.writeValueAsBytes(value));
        record.headers().add("event_id", event.eventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event_type", event.eventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event_version", Integer.toString(event.eventVersion()).getBytes(StandardCharsets.UTF_8));
        if (event.traceParent() != null && !event.traceParent().isBlank()) {
            record.headers().add("traceparent", event.traceParent().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    private IllegalStateException publicationFailed(EventEnvelope event, Throwable failure) {
        Producer<String, byte[]> failed = producer;
        producer = null;
        if (failed != null) {
            try {
                failed.close(Duration.ZERO);
            } catch (RuntimeException closing) {
                failure.addSuppressed(closing);
            }
        }
        return new IllegalStateException("Kafka did not acknowledge event " + event.eventId(), failure);
    }

    @Override
    public synchronized void close() {
        closed = true;
        Producer<String, byte[]> current = producer;
        producer = null;
        if (current != null) {
            current.close(Duration.ZERO);
        }
    }
}
package com.ridehailing.platform.outbox;

import com.ridehailing.platform.EventStream;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.SmartLifecycle;
import tools.jackson.databind.json.JsonMapper;

class KafkaEventStream implements EventStream, SmartLifecycle, AutoCloseable {

    static final String LOCATION_TOPIC = "location.updates";

    private final JsonMapper json;
    private final Supplier<Producer<String, byte[]>> factory;
    private final KafkaStreamProperties properties;
    private final Counter queued;
    private final Counter published;
    private final Counter failed;
    private final Counter full;
    private final Counter stopped;
    private final Counter oversized;
    private volatile Sender sender;
    private boolean closed;

    KafkaEventStream(JsonMapper json, List<String> brokers, KafkaStreamProperties properties, MeterRegistry metrics) {
        this(json, () -> new KafkaProducer<>(producerProperties(brokers, properties)), properties, metrics);
    }

    KafkaEventStream(JsonMapper json, Supplier<Producer<String, byte[]>> factory, KafkaStreamProperties properties,
            MeterRegistry metrics) {
        this.json = Objects.requireNonNull(json);
        this.factory = Objects.requireNonNull(factory);
        this.properties = Objects.requireNonNull(properties);
        queued = counter(metrics, "queued");
        published = counter(metrics, "published");
        failed = counter(metrics, "failed");
        full = counter(metrics, "queue_full");
        stopped = counter(metrics, "stopped");
        oversized = counter(metrics, "oversized");
        Gauge.builder("event_stream.queue.size", this, stream -> stream.pending()).tag("topic", LOCATION_TOPIC).register(metrics);
    }

    static Map<String, Object> producerProperties(List<String> brokers, KafkaStreamProperties properties) {
        if (brokers.isEmpty() || brokers.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Kafka stream needs explicit brokers");
        }
        int timeout = Math.toIntExact(properties.sendTimeout().toMillis());
        return Map.ofEntries(
                Map.entry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, List.copyOf(brokers)),
                Map.entry(ProducerConfig.CLIENT_ID_CONFIG, "ride-location-stream"),
                Map.entry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class),
                Map.entry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class),
                Map.entry(ProducerConfig.ACKS_CONFIG, "1"),
                Map.entry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false),
                Map.entry(ProducerConfig.RETRIES_CONFIG, 0),
                Map.entry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1),
                Map.entry(ProducerConfig.LINGER_MS_CONFIG, 20L),
                Map.entry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd"),
                Map.entry(ProducerConfig.BUFFER_MEMORY_CONFIG, properties.bufferBytes()),
                Map.entry(ProducerConfig.BATCH_SIZE_CONFIG, properties.maxRecordBytes() + 1_024),
                Map.entry(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, properties.maxRecordBytes() + 1_024),
                Map.entry(ProducerConfig.MAX_BLOCK_MS_CONFIG, Math.min(100L, timeout - 20L)),
                Map.entry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, timeout),
                Map.entry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, timeout - 20));
    }

    @Override
    public boolean offer(String topic, UUID key, Object value) {
        if (!LOCATION_TOPIC.equals(topic)) {
            throw new IllegalArgumentException("Unregistered best-effort topic " + topic);
        }
        Objects.requireNonNull(key, "Stream key");
        Objects.requireNonNull(value, "Stream value");
        Sender current = sender;
        if (current == null) {
            stopped.increment();
            return false;
        }
        try {
            current.executor.execute(() -> current.send(key, value));
            queued.increment();
            return true;
        } catch (RejectedExecutionException rejected) {
            (current.closed ? stopped : full).increment();
            return false;
        }
    }

    @Override
    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("Kafka event stream is closed");
        }
        if (sender == null) {
            sender = new Sender();
        }
    }

    @Override
    public synchronized void stop() {
        Sender current = sender;
        sender = null;
        if (current != null) {
            current.stop();
        }
    }

    @Override
    public boolean isRunning() {
        return sender != null;
    }

    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 50;
    }

    @Override
    public synchronized void close() {
        closed = true;
        stop();
    }

    private int pending() {
        Sender current = sender;
        return current == null ? 0 : current.executor.getQueue().size();
    }

    private static Counter counter(MeterRegistry metrics, String outcome) {
        return metrics.counter("event_stream.records", "topic", LOCATION_TOPIC, "outcome", outcome);
    }

    private final class Sender {

        private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()), Thread.ofVirtual().name("kafka-location-sender").factory());
        private final AtomicReference<Producer<String, byte[]>> producer = new AtomicReference<>();
        private volatile boolean closed;

        void send(UUID key, Object value) {
            try {
                if (closed) {
                    stopped.increment();
                    return;
                }
                byte[] bytes = json.writeValueAsBytes(value);
                if (bytes.length > properties.maxRecordBytes()) {
                    oversized.increment();
                    return;
                }
                Producer<String, byte[]> current = producer.get();
                if (current == null) {
                    current = Objects.requireNonNull(factory.get());
                    producer.set(current);
                }
                if (closed) {
                    stopped.increment();
                    return;
                }
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(LOCATION_TOPIC, key.toString(), bytes);
                record.headers().add("schema_version", "1".getBytes(StandardCharsets.UTF_8));
                current.send(record, (metadata, error) -> (error == null ? published : failed).increment());
            } catch (RuntimeException failure) {
                failed.increment();
            } finally {
                if (closed) {
                    closeProducer();
                }
            }
        }

        void stop() {
            closed = true;
            stopped.increment(executor.shutdownNow().size());
            boolean interrupted = Thread.currentThread().isInterrupted();
            try {
                closeProducer();
                executor.awaitTermination(properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interruption) {
                interrupted = true;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void closeProducer() {
            Producer<String, byte[]> current = producer.getAndSet(null);
            if (current != null) {
                current.close(Duration.ZERO);
            }
        }
    }
}
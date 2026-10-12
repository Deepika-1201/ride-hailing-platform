package com.ridehailing.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class KafkaEventStreamTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID DRIVER = UUID.fromString("0199a3f0-0000-7000-8000-000000000072");
    private static final String TOPIC = KafkaEventStream.LOCATION_TOPIC;
    private static final KafkaStreamProperties PROPERTIES = new KafkaStreamProperties(2, 256, 32_768,
            Duration.ofSeconds(2), Duration.ofMillis(50));

    @Test
    void usesLeaderAcknowledgementWithoutDurableRetriesAndBoundsProducerBuffers() {
        Map<String, Object> options = KafkaEventStream.producerProperties(List.of("localhost:9092"), PROPERTIES);

        assertThat(options).containsEntry(ProducerConfig.ACKS_CONFIG, "1")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false)
                .containsEntry(ProducerConfig.RETRIES_CONFIG, 0)
                .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1)
                .containsEntry(ProducerConfig.LINGER_MS_CONFIG, 20L)
                .containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd")
                .containsEntry(ProducerConfig.BUFFER_MEMORY_CONFIG, 32_768L)
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 100L)
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2_000)
                .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1_980);
        assertThat(new ProducerConfig(options).getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isFalse();
    }

    @Test
    void aStalledProducerCannotBlockCallersOrGrowTheQueue() throws Exception {
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch connect = new CountDownLatch(1);
        MockProducer<String, byte[]> producer = producer(true);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try (KafkaEventStream stream = new KafkaEventStream(JSON, () -> {
            creating.countDown();
            awaitUninterruptibly(connect);
            return producer;
        }, PROPERTIES, metrics); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            stream.start();
            assertThat(stream.offer(TOPIC, DRIVER, Map.of("seq", 1))).isTrue();
            try {
                assertThat(creating.await(5, TimeUnit.SECONDS)).isTrue();
                var accepted = callers.submit(() -> List.of(stream.offer(TOPIC, DRIVER, Map.of("seq", 2)),
                        stream.offer(TOPIC, DRIVER, Map.of("seq", 3)), stream.offer(TOPIC, DRIVER, Map.of("seq", 4))));
                assertThat(accepted.get(1, TimeUnit.SECONDS)).containsExactly(true, true, false);
                assertThat(metrics.get("event_stream.queue.size").gauge().value()).isEqualTo(2);
                assertThat(count(metrics, "queue_full")).isEqualTo(1);
            } finally {
                connect.countDown();
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(count(metrics, "published")).isEqualTo(3));
            assertThat(producer.history()).extracting(record -> JSON.readTree(record.value()).get("seq").asInt()).containsExactly(1, 2, 3);
            assertThat(producer.history()).allSatisfy(record -> {
                assertThat(record.topic()).isEqualTo(TOPIC);
                assertThat(record.key()).isEqualTo(DRIVER.toString());
                assertThat(new String(record.headers().lastHeader("schema_version").value(), StandardCharsets.UTF_8)).isEqualTo("1");
            });
        }
        assertThat(producer.closed()).isTrue();
    }

    @Test
    void asynchronousDeliveryErrorsAreCountedAndLaterUpdatesCanSucceed() {
        MockProducer<String, byte[]> producer = producer(false);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try (KafkaEventStream stream = new KafkaEventStream(JSON, () -> producer, PROPERTIES, metrics)) {
            stream.start();
            assertThat(stream.offer(TOPIC, DRIVER, Map.of("seq", 1))).isTrue();
            await().atMost(Duration.ofSeconds(5)).until(() -> producer.errorNext(new KafkaException("broker unavailable")));
            assertThat(count(metrics, "failed")).isEqualTo(1);
            assertThat(count(metrics, "published")).isZero();

            assertThat(stream.offer(TOPIC, DRIVER, Map.of("seq", 2))).isTrue();
            await().atMost(Duration.ofSeconds(5)).until(producer::completeNext);
            assertThat(count(metrics, "published")).isEqualTo(1);
            assertThat(producer.closed()).isFalse();
        }
    }

    @Test
    void oversizeValuesAndSynchronousFailuresDoNotKillTheSender() {
        MockProducer<String, byte[]> producer = producer(true);
        AtomicInteger created = new AtomicInteger();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try (KafkaEventStream stream = new KafkaEventStream(JSON, () -> {
            created.incrementAndGet();
            return producer;
        }, PROPERTIES, metrics)) {
            stream.start();
            assertThat(stream.offer(TOPIC, DRIVER, Map.of("value", "x".repeat(300)))).isTrue();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(count(metrics, "oversized")).isEqualTo(1));
            assertThat(created).hasValue(0);
            producer.sendException = new KafkaException("metadata unavailable");
            assertThat(stream.offer(TOPIC, DRIVER, Map.of("seq", 1))).isTrue();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(count(metrics, "failed")).isEqualTo(1));
            producer.sendException = null;
            assertThat(stream.offer(TOPIC, DRIVER, Map.of("seq", 2))).isTrue();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(count(metrics, "published")).isEqualTo(1));
        }
    }

    @Test
    void aSerializationFailureIsDroppedBeforeCreatingAProducerAndLaterOffersStillWork() {
        MockProducer<String, byte[]> producer = producer(true);
        AtomicInteger created = new AtomicInteger();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try (KafkaEventStream stream = new KafkaEventStream(JSON, () -> {
            created.incrementAndGet();
            return producer;
        }, PROPERTIES, metrics)) {
            stream.start();
            assertThat(stream.offer(TOPIC, DRIVER, new BrokenValue("unreadable"))).isTrue();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(count(metrics, "failed")).isEqualTo(1));
            assertThat(created).hasValue(0);
            assertThat(stream.offer(TOPIC, DRIVER, Map.of("seq", 1))).isTrue();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(count(metrics, "published")).isEqualTo(1));
        }
    }

    @Test
    void shutdownDuringLazyCreationIsBoundedAndRestartDoesNotReuseTheOldSender() throws Exception {
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch connect = new CountDownLatch(1);
        MockProducer<String, byte[]> abandoned = producer(true);
        MockProducer<String, byte[]> fresh = producer(true);
        AtomicInteger created = new AtomicInteger();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try (KafkaEventStream stream = new KafkaEventStream(JSON, () -> {
            if (created.getAndIncrement() == 0) {
                creating.countDown();
                awaitUninterruptibly(connect);
                return abandoned;
            }
            return fresh;
        }, PROPERTIES, metrics); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(stream.offer(TOPIC, DRIVER, Map.of())).isFalse();
            stream.start();
            assertThat(stream.offer(TOPIC, DRIVER, Map.of("seq", 1))).isTrue();
            try {
                assertThat(creating.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(stream.offer(TOPIC, DRIVER, Map.of("seq", 2))).isTrue();
                callers.submit(() -> {
                    stream.stop();
                }).get(1, TimeUnit.SECONDS);
                assertThat(stream.isRunning()).isFalse();
                assertThat(stream.offer(TOPIC, DRIVER, Map.of("seq", 3))).isFalse();
                stream.start();
                assertThat(stream.offer(TOPIC, DRIVER, Map.of("seq", 4))).isTrue();
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(count(metrics, "published")).isEqualTo(1));
            } finally {
                connect.countDown();
            }
            await().atMost(Duration.ofSeconds(5)).until(abandoned::closed);
            assertThat(abandoned.history()).isEmpty();
            assertThat(fresh.history()).singleElement().satisfies(record -> assertThat(JSON.readTree(record.value()).get("seq").asInt())
                    .isEqualTo(4));
        }
        assertThat(fresh.closed()).isTrue();
    }

    @Test
    void invalidTopicsAndClosedLifecycleDoNotCreateAProducer() {
        AtomicInteger created = new AtomicInteger();
        KafkaEventStream stream = new KafkaEventStream(JSON, () -> {
            created.incrementAndGet();
            return producer(true);
        }, PROPERTIES, new SimpleMeterRegistry());
        assertThatThrownBy(() -> stream.offer("rides.events", DRIVER, Map.of())).isInstanceOf(IllegalArgumentException.class);
        stream.close();
        assertThat(stream.offer(TOPIC, DRIVER, Map.of())).isFalse();
        assertThatThrownBy(stream::start).isInstanceOf(IllegalStateException.class);
        assertThat(created).hasValue(0);
    }

    record BrokenValue(String value) {

        public String value() {
            throw new IllegalStateException("cannot serialize value");
        }
    }

    private static double count(SimpleMeterRegistry metrics, String outcome) {
        return metrics.get("event_stream.records").tag("outcome", outcome).counter().count();
    }

    private static MockProducer<String, byte[]> producer(boolean autoComplete) {
        return new MockProducer<>(autoComplete, null, new StringSerializer(), new ByteArraySerializer());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (!latch.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test connection gate timed out");
                    }
                    return;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
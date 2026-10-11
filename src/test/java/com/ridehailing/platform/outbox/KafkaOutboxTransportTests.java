package com.ridehailing.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.platform.EventEnvelope;
import com.ridehailing.support.EventContract;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class KafkaOutboxTransportTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-11T00:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("0199a3f0-0000-7000-8000-000000000001");
    private static final UUID AGGREGATE_ID = UUID.fromString("0199a3f0-0000-7000-8000-000000000002");
    private static final UUID PARTITION_KEY = UUID.fromString("0199a3f0-0000-7000-8000-000000000003");
    private static final String TRACE = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";

    @ParameterizedTest
    @CsvSource({
            "RideRequested,rides.events", "DriverAssigned,rides.events", "DriverUnassigned,rides.events",
            "DriverArrived,rides.events", "TripStarted,rides.events", "TripCompleted,rides.events",
            "RideCancelled,rides.events", "RideNotMatched,rides.events", "RatingSubmitted,rides.events",
            "OfferCreated,dispatch.events", "OfferAccepted,dispatch.events", "OfferDeclined,dispatch.events",
            "OfferExpired,dispatch.events", "OfferWithdrawn,dispatch.events", "DriverWentOnline,drivers.events",
            "DriverWentOffline,drivers.events", "DriverVerified,drivers.events", "DriverSuspended,drivers.events",
            "DriverReinstated,drivers.events", "ChargeSucceeded,payments.events", "ChargeFailed,payments.events",
            "RefundSucceeded,payments.events"
    })
    void routesEveryCatalogEvent(String eventType, String topic) {
        assertThat(KafkaTopics.forEvent(eventType)).isEqualTo(topic);
    }

    @Test
    void everyDocumentedEventHasAnExplicitTopic() throws Exception {
        try (var schemas = Files.list(Path.of("docs/schemas/events"))) {
            List<String> types = schemas.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("[A-Z]\\w+\\.v\\d+\\.json"))
                    .map(name -> name.substring(0, name.indexOf(".v"))).distinct().toList();
            assertThat(types).hasSize(22);
            for (String type : types) {
                assertThat(KafkaTopics.forEvent(type)).endsWith(".events");
            }
        }
    }

    @Test
    void publishesTheContractEnvelopeWithStoredPartitionKeyAndHeaders() {
        MockProducer<String, byte[]> producer = producer(true);
        EventEnvelope event = event("DriverVerified", "request-1", TRACE);
        try (KafkaOutboxTransport transport = new KafkaOutboxTransport(JSON, () -> producer, Duration.ofSeconds(1))) {
            transport.publish(new OutboxRows.Row(12, event));
        }

        assertThat(producer.history()).hasSize(1);
        var record = producer.history().getFirst();
        assertThat(record.topic()).isEqualTo("drivers.events");
        assertThat(record.key()).isEqualTo(PARTITION_KEY.toString()).isNotEqualTo(AGGREGATE_ID.toString());
        JsonNode value = JSON.readTree(record.value());
        EventContract.assertConforms(value);
        assertThat(value.has("partition_key")).isFalse();
        assertThat(value.get("aggregate_version").asLong()).isEqualTo(9007199254740993L);
        assertThat(value.get("payload")).isEqualTo(event.payload());
        assertThat(value.get("trace_parent").asString()).isEqualTo(TRACE);
        assertThat(value.get("causation_id").asString()).isEqualTo("request-1");
        assertThat(record.headers().toArray()).hasSize(4);
        assertThat(new String(record.headers().lastHeader("event_id").value(), StandardCharsets.UTF_8)).isEqualTo(EVENT_ID.toString());
        assertThat(new String(record.headers().lastHeader("event_type").value(), StandardCharsets.UTF_8)).isEqualTo("DriverVerified");
        assertThat(new String(record.headers().lastHeader("event_version").value(), StandardCharsets.UTF_8)).isEqualTo("1");
        assertThat(new String(record.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8)).isEqualTo(TRACE);
    }

    @Test
    void absentOptionalContextIsOmittedFromTheEnvelopeAndHeaders() {
        MockProducer<String, byte[]> producer = producer(true);
        try (KafkaOutboxTransport transport = new KafkaOutboxTransport(JSON, () -> producer, Duration.ofSeconds(1))) {
            transport.publish(new OutboxRows.Row(1, event("DriverVerified", null, "")));
        }
        var record = producer.history().getFirst();
        JsonNode value = JSON.readTree(record.value());
        EventContract.assertConforms(value);
        assertThat(value.has("causation_id")).isFalse();
        assertThat(value.has("trace_parent")).isFalse();
        assertThat(record.headers().lastHeader("traceparent")).isNull();
    }

    @Test
    void configuresIdempotencyAllAcknowledgementsAndBoundedMetadataWait() {
        Map<String, Object> properties = KafkaOutboxTransport.producerProperties(List.of("localhost:29092"), Duration.ofSeconds(5));
        assertThat(properties).containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5)
                .containsEntry(ProducerConfig.LINGER_MS_CONFIG, 5L)
                .containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd")
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000L)
                .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    }

    @Test
    void aMissingAcknowledgementClosesTheProducerAndTheNextAttemptUsesAFreshOne() {
        MockProducer<String, byte[]> stalled = producer(false);
        MockProducer<String, byte[]> recovered = producer(true);
        var producers = new ArrayDeque<>(List.of(stalled, recovered));
        try (KafkaOutboxTransport transport = new KafkaOutboxTransport(JSON, producers::removeFirst, Duration.ofMillis(1))) {
            var row = new OutboxRows.Row(1, event("DriverVerified", null, null));
            assertThatThrownBy(() -> transport.publish(row)).isInstanceOf(IllegalStateException.class)
                    .hasRootCauseInstanceOf(TimeoutException.class);
            assertThat(stalled.closed()).isTrue();

            transport.publish(row);

            assertThat(producers).isEmpty();
            assertThat(recovered.history()).hasSize(1);
        }
    }

    @Test
    void aSynchronousProducerFailureIsPropagatedAndTheProducerIsDiscarded() {
        MockProducer<String, byte[]> failed = producer(true);
        failed.sendException = new KafkaException("broker unavailable");
        try (KafkaOutboxTransport transport = new KafkaOutboxTransport(JSON, () -> failed, Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> transport.publish(new OutboxRows.Row(1, event("DriverVerified", null, null))))
                    .isInstanceOf(IllegalStateException.class).hasRootCauseMessage("broker unavailable");
            assertThat(failed.closed()).isTrue();
        }
    }

    @Test
    void anAsynchronousBrokerRejectionPreservesItsCauseAndDiscardsTheProducer() {
        MockProducer<String, byte[]> rejected = new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer()) {
            @Override
            public synchronized Future<RecordMetadata> send(ProducerRecord<String, byte[]> record) {
                return CompletableFuture.failedFuture(new KafkaException("broker refused the record"));
            }
        };
        try (KafkaOutboxTransport transport = new KafkaOutboxTransport(JSON, () -> rejected, Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> transport.publish(new OutboxRows.Row(1, event("DriverVerified", null, null))))
                    .isInstanceOf(IllegalStateException.class).hasRootCauseMessage("broker refused the record");
            assertThat(rejected.closed()).isTrue();
        }
    }

    @Test
    void interruptionPreservesTheFlagAndDiscardsTheProducer() {
        MockProducer<String, byte[]> stalled = new MockProducer<>(false, null, new StringSerializer(), new ByteArraySerializer()) {
            @Override
            public void close(Duration timeout) {
                Thread.interrupted();
                super.close(timeout);
            }
        };
        try (KafkaOutboxTransport transport = new KafkaOutboxTransport(JSON, () -> stalled, Duration.ofSeconds(1))) {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> transport.publish(new OutboxRows.Row(1, event("DriverVerified", null, null))))
                        .hasRootCauseInstanceOf(InterruptedException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(stalled.closed()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void unknownEventsAndClosedTransportNeverCreateAProducer() {
        AtomicInteger created = new AtomicInteger();
        KafkaOutboxTransport transport = new KafkaOutboxTransport(JSON, () -> {
            created.incrementAndGet();
            return producer(true);
        }, Duration.ofSeconds(1));
        assertThatThrownBy(() -> transport.publish(new OutboxRows.Row(1, event("UnknownEvent", null, null))))
                .isInstanceOf(IllegalArgumentException.class);
        transport.close();
        assertThatThrownBy(() -> transport.publish(new OutboxRows.Row(1, event("DriverVerified", null, null))))
                .isInstanceOf(IllegalStateException.class).hasMessage("Kafka outbox transport is closed");
        assertThat(created).hasValue(0);
    }

    private static MockProducer<String, byte[]> producer(boolean acknowledge) {
        return new MockProducer<>(acknowledge, null, new StringSerializer(), new ByteArraySerializer());
    }

    private static EventEnvelope event(String type, String causation, String trace) {
        var payload = JSON.createObjectNode();
        payload.put("driver_id", AGGREGATE_ID.toString());
        payload.put("city_id", "blr");
        payload.put("verified_at", OCCURRED_AT.toString());
        payload.put("verified_by", PARTITION_KEY.toString());
        return new EventEnvelope(EVENT_ID, type, 1, "driver", AGGREGATE_ID, 9007199254740993L, PARTITION_KEY,
                OCCURRED_AT, "driver/worker", "correlation-1", causation, trace, payload);
    }
}
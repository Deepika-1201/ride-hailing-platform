package com.ridehailing.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.EventEnvelope;
import com.ridehailing.platform.InstanceId;
import com.ridehailing.platform.Leases;
import com.ridehailing.platform.LogContext;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.leases.LeaseProperties;
import com.ridehailing.platform.workers.WorkerProperties;
import com.ridehailing.support.EventContract;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.Kafkas;
import com.ridehailing.support.PlatformTables;
import com.ridehailing.support.Effects;
import com.ridehailing.support.ScriptedConsumer;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class KafkaOutboxIntegrationTests extends IntegrationTest {

    private static final String TOPIC = "drivers.events";
    private static final UUID DRIVER = UUID.fromString("0199a3f0-0000-7000-8000-000000000042");
    private static final UUID STAFF = UUID.fromString("0199a3f0-0000-7000-8000-000000000043");
    private static final Duration WAIT = Duration.ofSeconds(5);

    @Autowired
    private Outbox outbox;

    @Autowired
    private OutboxRows rows;

    @Autowired
    private ConsumerDelivery delivery;

    @Autowired
    private OutboxTransport selectedTransport;

    @Autowired
    private Leases leases;

    @Autowired
    private OutboxProperties properties;

    @Autowired
    private LeaseProperties leaseProperties;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper mapper;

    @BeforeAll
    static void createTopic() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, Kafkas.broker().getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void reset() {
        PlatformTables.reset(jdbc);
        Effects.reset(jdbc);
    }

    @Test
    void theDatabaseRelayPublishesContractEnvelopesButTheDefaultTransportRemainsInProcess() {
        assertThat(selectedTransport).isInstanceOf(InProcessOutboxTransport.class);
        UUID eventId = append(1);
        try (KafkaConsumer<String, byte[]> consumer = consumerAtEnd();
                KafkaOutboxTransport transport = new KafkaOutboxTransport(mapper, List.of(Kafkas.broker().getBootstrapServers()), WAIT)) {
            relay(transport).relayBatch();

            assertThat(unpublished()).isZero();
            List<ConsumerRecord<String, byte[]>> received = receive(consumer, Set.of(eventId));
            assertThat(received).hasSize(1);
            ConsumerRecord<String, byte[]> record = received.getFirst();
            assertThat(record.key()).isEqualTo(DRIVER.toString());
            JsonNode envelope = mapper.readTree(record.value());
            EventContract.assertConforms(envelope);
            assertThat(envelope.get("event_id").asString()).isEqualTo(eventId.toString());
            assertThat(envelope.has("partition_key")).isFalse();
            assertThat(new String(record.headers().lastHeader("event_id").value(), StandardCharsets.UTF_8)).isEqualTo(eventId.toString());
            assertThat(new String(record.headers().lastHeader("event_type").value(), StandardCharsets.UTF_8)).isEqualTo("DriverVerified");
        }
    }

    @Test
    void aBrokerPauseLeavesTheBatchPendingAndTheSameTransportRecoversWithAFreshProducer() {
        UUID first = append(1);
        UUID second = append(2);
        AtomicBoolean paused = new AtomicBoolean();
        try (KafkaConsumer<String, byte[]> consumer = consumerAtEnd();
                KafkaOutboxTransport transport = new KafkaOutboxTransport(mapper, List.of(Kafkas.broker().getBootstrapServers()), WAIT)) {
            OutboxTransport interrupted = row -> {
                transport.publish(row);
                if (row.envelope().eventId().equals(first)) {
                    paused.set(true);
                    Kafkas.broker().getDockerClient().pauseContainerCmd(Kafkas.broker().getContainerId()).exec();
                }
            };
            try {
                assertThatThrownBy(() -> relay(interrupted).relayBatch()).isInstanceOf(IllegalStateException.class)
                        .hasRootCauseInstanceOf(TimeoutException.class);
                assertThat(unpublished()).isEqualTo(2);
            } finally {
                if (paused.getAndSet(false)) {
                    Kafkas.broker().getDockerClient().unpauseContainerCmd(Kafkas.broker().getContainerId()).exec();
                }
            }

            jdbc.sql("UPDATE platform.leases SET expires_at = now() - interval '1 second' WHERE name = :name")
                    .param("name", OutboxRelay.LEASE).update();
            relay(transport).relayBatch();

            assertThat(unpublished()).isZero();
            List<ConsumerRecord<String, byte[]>> received = receive(consumer, Set.of(first, second));
            assertThat(received).allSatisfy(record -> EventContract.assertConforms(mapper.readTree(record.value())));
        }
    }

    @Test
    void aKafkaHandlerFailureRollsBackTheInboxAndLeavesTheRecordUnacknowledged() {
        UUID eventId = append(1);
        ConsumerRecord<String, byte[]> record = record(eventId);
        ScriptedConsumer consumer = driverConsumer();
        AtomicInteger acknowledged = new AtomicInteger();
        consumer.afterEffect(event -> {
            assertThat(MDC.get(LogContext.ROLE)).isEqualTo("worker");
            assertThat(MDC.get(LogContext.CORRELATION_ID)).isEqualTo("kafka-delivery-test");
            assertThat(MDC.get(LogContext.CAUSATION_ID)).isEqualTo(eventId.toString());
            throw new IllegalStateException("handler failed after writing");
        });
        KafkaEventListener listener = new KafkaEventListener(consumer, delivery, mapper);

        assertThatThrownBy(() -> listener.onMessage(record, acknowledged::incrementAndGet))
                .isInstanceOf(IllegalStateException.class).hasMessage("handler failed after writing");

        assertThat(acknowledged.get()).isZero();
        assertThat(Effects.refs(jdbc, consumer.name())).isEmpty();
        assertThat(inbox(consumer.name(), eventId)).isZero();
        consumer.reset();
        listener.onMessage(record, () -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(Effects.refs(jdbc, consumer.name())).containsExactly(eventId.toString());
            assertThat(inbox(consumer.name(), eventId)).isEqualTo(1);
            acknowledged.incrementAndGet();
        });
        assertThat(acknowledged.get()).isEqualTo(1);
    }

    @Test
    void anOffsetAcknowledgmentFailureDoesNotRepeatTheCommittedEffect() {
        UUID eventId = append(1);
        ConsumerRecord<String, byte[]> record = record(eventId);
        ScriptedConsumer consumer = driverConsumer();
        KafkaEventListener listener = new KafkaEventListener(consumer, delivery, mapper);

        assertThatThrownBy(() -> listener.onMessage(record, () -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new KafkaException("offset commit unavailable");
        })).isInstanceOf(KafkaException.class).hasMessage("offset commit unavailable");

        AtomicInteger acknowledged = new AtomicInteger();
        listener.onMessage(record, acknowledged::incrementAndGet);

        assertThat(acknowledged.get()).isEqualTo(1);
        assertThat(Effects.refs(jdbc, consumer.name())).containsExactly(eventId.toString());
        assertThat(inbox(consumer.name(), eventId)).isEqualTo(1);
    }

    @Test
    void anUnsubscribedEventOnTheSameTopicIsAcknowledgedWithoutInvokingTheHandler() {
        UUID eventId = append(1);
        ScriptedConsumer consumer = new ScriptedConsumer("kafka.unsubscribed", jdbc);
        AtomicInteger acknowledged = new AtomicInteger();
        new KafkaEventListener(consumer, delivery, mapper).onMessage(record(eventId), acknowledged::incrementAndGet);

        assertThat(acknowledged.get()).isEqualTo(1);
        assertThat(Effects.refs(jdbc, consumer.name())).isEmpty();
        assertThat(inbox(consumer.name(), eventId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 9007199254740993L})
    void schemaValidAggregateVersionsReachTheConsumerWithoutLosingPrecision(long version) {
        UUID eventId = append(1);
        ConsumerRecord<String, byte[]> source = record(eventId);
        ObjectNode value = (ObjectNode) mapper.readTree(source.value());
        value.put("aggregate_version", version);
        EventContract.assertConforms(value);
        ConsumerRecord<String, byte[]> received = new ConsumerRecord<>(TOPIC, 0, 0, source.key(), mapper.writeValueAsBytes(value));
        source.headers().forEach(header -> received.headers().add(header));
        ScriptedConsumer consumer = driverConsumer();
        consumer.afterEffect(event -> assertThat(event.aggregateVersion()).isEqualTo(version));
        AtomicInteger acknowledged = new AtomicInteger();

        new KafkaEventListener(consumer, delivery, mapper).onMessage(received, acknowledged::incrementAndGet);

        assertThat(acknowledged.get()).isEqualTo(1);
        assertThat(Effects.refs(jdbc, consumer.name())).containsExactly(eventId.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-json", "tombstone", "invalid-key", "noncanonical-key", "wrong-topic",
        "missing-aggregate-version", "negative-aggregate-version", "fractional-aggregate-version", "unknown-field",
        "missing-header", "duplicate-header", "conflicting-header", "unsupported-event-version", "trailing-json"})
    void malformedKafkaRecordsAreNotDeliveredOrAcknowledged(String defect) {
        UUID eventId = append(1);
        ConsumerRecord<String, byte[]> source = record(eventId);
        ObjectNode value = (ObjectNode) mapper.readTree(source.value());
        switch (defect) {
            case "missing-aggregate-version" -> value.remove("aggregate_version");
            case "negative-aggregate-version" -> value.put("aggregate_version", -1);
            case "fractional-aggregate-version" -> value.put("aggregate_version", 1.5);
            case "unknown-field" -> value.put("partition_key", source.key());
            case "unsupported-event-version" -> value.put("event_version", 2);
            default -> { }
        }
        byte[] bytes = switch (defect) {
            case "invalid-json" -> "{".getBytes(StandardCharsets.UTF_8);
            case "tombstone" -> null;
            case "trailing-json" -> (mapper.writeValueAsString(value) + " {}").getBytes(StandardCharsets.UTF_8);
            default -> mapper.writeValueAsBytes(value);
        };
        String key = switch (defect) {
            case "invalid-key" -> "not-a-uuid";
            case "noncanonical-key" -> "0-0-0-0-1";
            default -> source.key();
        };
        ConsumerRecord<String, byte[]> received = new ConsumerRecord<>(
                defect.equals("wrong-topic") ? "rides.events" : TOPIC, 0, 0, key, bytes);
        source.headers().forEach(header -> received.headers().add(header));
        switch (defect) {
            case "missing-header" -> received.headers().remove("event_id");
            case "duplicate-header" -> received.headers().add(source.headers().lastHeader("event_id"));
            case "conflicting-header" -> {
                received.headers().remove("event_id");
                received.headers().add("event_id", DRIVER.toString().getBytes(StandardCharsets.UTF_8));
            }
            case "unsupported-event-version" -> {
                received.headers().remove("event_version");
                received.headers().add("event_version", "2".getBytes(StandardCharsets.UTF_8));
            }
            default -> { }
        }
        ScriptedConsumer consumer = driverConsumer();
        AtomicInteger acknowledged = new AtomicInteger();

        assertThatThrownBy(() -> new KafkaEventListener(consumer, delivery, mapper)
                .onMessage(received, acknowledged::incrementAndGet)).isInstanceOf(SerializationException.class);

        assertThat(acknowledged.get()).isZero();
        assertThat(Effects.refs(jdbc, consumer.name())).isEmpty();
        assertThat(inbox(consumer.name(), eventId)).isZero();
    }

    @Test
    void theContainerRetriesAtOneTwoAndFourSeconds() {
        BackOffExecution retries = KafkaEventConsumer.retryBackOff().start();
        assertThat(retries.nextBackOff()).isEqualTo(1_000);
        assertThat(retries.nextBackOff()).isEqualTo(2_000);
        assertThat(retries.nextBackOff()).isEqualTo(4_000);
        assertThat(retries.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void aListenerContainerRetriesRolledBackWorkAndCommitsItsGroupOffset() throws Exception {
        UUID eventId = append(1);
        ConsumerRecord<String, byte[]> source = record(eventId);
        ScriptedConsumer consumer = driverConsumer("kafka.retry-" + UUID.randomUUID());
        initializeGroup(consumer.name());
        AtomicInteger attempts = new AtomicInteger();
        consumer.afterEffect(event -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("temporary handler failure");
            }
        });
        try (KafkaEventConsumer listener = listener(consumer, WAIT)) {
            listener.start();
            long offset = publish(source);
            awaitCommitted(consumer.name(), offset + 1);

            assertThat(attempts.get()).isEqualTo(3);
            assertThat(inbox(consumer.name(), eventId)).isEqualTo(1);
            assertThat(Effects.refs(jdbc, consumer.name())).containsExactly(eventId.toString());
        }
        try (KafkaEventConsumer restarted = listener(consumer, WAIT)) {
            restarted.start();
            long repeatedOffset = publish(source);
            awaitCommitted(consumer.name(), repeatedOffset + 1);

            assertThat(attempts.get()).isEqualTo(3);
            assertThat(inbox(consumer.name(), eventId)).isEqualTo(1);
            assertThat(Effects.refs(jdbc, consumer.name())).containsExactly(eventId.toString());
        }
    }

    @Test
    void exhaustedRetriesPublishTheOriginalRecordToTheGroupsDeadLetterTopic() throws Exception {
        UUID eventId = append(1);
        ConsumerRecord<String, byte[]> source = record(eventId);
        ScriptedConsumer consumer = driverConsumer("kafka.exhausted-" + UUID.randomUUID());
        initializeGroup(consumer.name());
        String deadLetterTopic = createDeadLetterTopic(consumer.name(), Map.of());
        AtomicInteger attempts = new AtomicInteger();
        consumer.afterEffect(event -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("permanent handler failure");
        });
        try (KafkaConsumer<String, byte[]> deadLetters = consumerAtEnd(deadLetterTopic);
                KafkaEventConsumer listener = listener(consumer, WAIT)) {
            listener.start();
            long offset = publish(source);
            awaitCommitted(consumer.name(), offset + 1);

            List<ConsumerRecord<String, byte[]>> records = receive(deadLetters, Set.of(eventId));
            assertThat(records).hasSize(1);
            ConsumerRecord<String, byte[]> deadLetter = records.getFirst();
            assertThat(deadLetter.value()).containsExactly(source.value());
            assertThat(deadLetter.key()).isEqualTo(source.key());
            assertThat(new String(deadLetter.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
                    StandardCharsets.UTF_8)).isEqualTo(TOPIC);
            assertThat(new String(deadLetter.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP).value(),
                    StandardCharsets.UTF_8)).isEqualTo(consumer.name());
            assertThat(attempts.get()).isEqualTo(4);
            assertThat(inbox(consumer.name(), eventId)).isZero();
            assertThat(Effects.refs(jdbc, consumer.name())).isEmpty();
        }
    }

    @Test
    void anUnacknowledgedDeadLetterDoesNotAdvanceTheSourceOffset() throws Exception {
        UUID eventId = append(1);
        ScriptedConsumer consumer = driverConsumer("kafka.recovery-" + UUID.randomUUID());
        long startingOffset = initializeGroup(consumer.name());
        String deadLetterTopic = createDeadLetterTopic(consumer.name(), Map.of(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "1"));
        AtomicInteger attempts = new AtomicInteger();
        consumer.afterEffect(event -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("handler must be recovered");
        });
        try (Admin admin = admin();
                KafkaConsumer<String, byte[]> deadLetters = consumerAtEnd(deadLetterTopic);
                KafkaEventConsumer listener = listener(consumer, Duration.ofSeconds(1))) {
            listener.start();
            long offset = publish(record(eventId));
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(attempts.get())
                    .as("handler attempts after recovery, committed source offset %s", committed(admin, consumer.name()))
                    .isGreaterThan(4));

            assertThat(committed(admin, consumer.name())).isEqualTo(startingOffset);
            assertThat(inbox(consumer.name(), eventId)).isZero();
            assertThat(Effects.refs(jdbc, consumer.name())).isEmpty();
                TopicPartition deadLetterPartition = new TopicPartition(deadLetterTopic, 0);
                assertThat(admin.listOffsets(Map.of(deadLetterPartition, OffsetSpec.latest())).all()
                    .get(5, TimeUnit.SECONDS).get(deadLetterPartition).offset()).isZero();
            admin.incrementalAlterConfigs(Map.of(new ConfigResource(ConfigResource.Type.TOPIC, deadLetterTopic),
                    List.of(new AlterConfigOp(new ConfigEntry(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "1048576"),
                            AlterConfigOp.OpType.SET)))).all().get(5, TimeUnit.SECONDS);
            awaitCommitted(consumer.name(), offset + 1);

            assertThat(receive(deadLetters, Set.of(eventId))).isNotEmpty();
            assertThat(Effects.refs(jdbc, consumer.name())).isEmpty();
        }
    }

    @Test
    void malformedRecordsGoStraightToDeadLettersWithoutInvokingTheHandler() throws Exception {
        UUID eventId = append(1);
        ConsumerRecord<String, byte[]> valid = record(eventId);
        byte[] malformed = "{invalid-json".getBytes(StandardCharsets.UTF_8);
        ConsumerRecord<String, byte[]> source = new ConsumerRecord<>(TOPIC, 0, 0, valid.key(), malformed);
        valid.headers().forEach(header -> source.headers().add(header));
        ScriptedConsumer consumer = driverConsumer("kafka.malformed-" + UUID.randomUUID());
        initializeGroup(consumer.name());
        String deadLetterTopic = createDeadLetterTopic(consumer.name(), Map.of());
        AtomicInteger attempts = new AtomicInteger();
        consumer.afterEffect(event -> attempts.incrementAndGet());
        try (KafkaConsumer<String, byte[]> deadLetters = consumerAtEnd(deadLetterTopic);
                KafkaEventConsumer listener = listener(consumer, WAIT)) {
            listener.start();
            long offset = publish(source);
            awaitCommitted(consumer.name(), offset + 1);

            List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                deadLetters.poll(Duration.ofMillis(100)).forEach(records::add);
                assertThat(records).hasSize(1);
            });
            ConsumerRecord<String, byte[]> deadLetter = records.getFirst();
            assertThat(deadLetter.value()).containsExactly(malformed);
            assertThat(ByteBuffer.wrap(deadLetter.headers().lastHeader(KafkaHeaders.DELIVERY_ATTEMPT).value())
                    .getInt()).isEqualTo(1);
            assertThat(attempts.get()).isZero();
            assertThat(inbox(consumer.name(), eventId)).isZero();
            assertThat(Effects.refs(jdbc, consumer.name())).isEmpty();
        }
    }

    private KafkaEventConsumer listener(ScriptedConsumer consumer, Duration timeout) {
        return new KafkaEventConsumer(consumer, delivery, mapper, List.of(Kafkas.broker().getBootstrapServers()),
                timeout, new FixedBackOff(10, 3));
    }

    private static Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, Kafkas.broker().getBootstrapServers()));
    }

    private long initializeGroup(String group) throws Exception {
        try (Admin admin = admin()) {
            TopicPartition partition = new TopicPartition(TOPIC, 0);
            long offset = admin.listOffsets(Map.of(partition, OffsetSpec.latest())).all()
                    .get(5, TimeUnit.SECONDS).get(partition).offset();
            admin.alterConsumerGroupOffsets(group, Map.of(partition, new OffsetAndMetadata(offset))).all()
                    .get(5, TimeUnit.SECONDS);
            return offset;
        }
    }

    private String createDeadLetterTopic(String group, Map<String, String> topicConfig) throws Exception {
        String topic = TOPIC + ".dlq." + group;
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1).configs(topicConfig)))
                    .all().get(5, TimeUnit.SECONDS);
        }
        return topic;
    }

    private long publish(ConsumerRecord<String, byte[]> record) throws Exception {
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(KafkaOutboxTransport.producerProperties(
                List.of(Kafkas.broker().getBootstrapServers()), WAIT))) {
            ProducerRecord<String, byte[]> sent = new ProducerRecord<>(record.topic(), record.key(), record.value());
            record.headers().forEach(header -> sent.headers().add(header));
            return producer.send(sent).get(5, TimeUnit.SECONDS).offset();
        }
    }

    private void awaitCommitted(String group, long offset) {
        try (Admin admin = admin()) {
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> assertThat(committed(admin, group)).isEqualTo(offset));
        }
    }

    private long committed(Admin admin, String group) throws Exception {
        return admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                .get(5, TimeUnit.SECONDS).get(new TopicPartition(TOPIC, 0)).offset();
    }

    private ScriptedConsumer driverConsumer() {
        return driverConsumer("kafka.drivers");
    }

    private ScriptedConsumer driverConsumer(String name) {
        return new ScriptedConsumer(name, jdbc) {
            @Override
            public Set<String> eventTypes() {
                return Set.of("DriverVerified");
            }
        };
    }

    private long inbox(String consumer, UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM platform.inbox WHERE consumer = :consumer AND event_id = :eventId")
                .param("consumer", consumer).param("eventId", eventId).query(Long.class).single();
    }

    private ConsumerRecord<String, byte[]> record(UUID eventId) {
        Instant occurredAt = Instant.now();
        EventEnvelope event = new EventEnvelope(eventId, "DriverVerified", 1, "driver", DRIVER, 1, DRIVER,
                occurredAt, "driver/worker", "kafka-delivery-test", null, null,
                mapper.valueToTree(new Verified(DRIVER, "blr", occurredAt, STAFF)));
        MockProducer<String, byte[]> producer = new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer());
        try (KafkaOutboxTransport transport = new KafkaOutboxTransport(mapper, () -> producer, WAIT)) {
            transport.publish(new OutboxRows.Row(1, event));
            ProducerRecord<String, byte[]> sent = producer.history().getFirst();
            ConsumerRecord<String, byte[]> received = new ConsumerRecord<>(sent.topic(), 0, 0, sent.key(), sent.value());
            sent.headers().forEach(header -> received.headers().add(header));
            return received;
        }
    }

    private UUID append(long version) {
        LogContext.run(Map.of(LogContext.ROLE, "api"), () -> transactions.executeWithoutResult(status ->
                outbox.append(DomainEvent.of("DriverVerified", 1, "driver", DRIVER, version,
                        new Verified(DRIVER, "blr", Instant.now(), STAFF)))));
        return jdbc.sql("SELECT event_id FROM platform.outbox ORDER BY id DESC LIMIT 1").query(UUID.class).single();
    }

    private OutboxRelay relay(OutboxTransport transport) {
        return new OutboxRelay(rows, transport, leases, new InstanceId("java-kafka-test"), properties, leaseProperties,
                new WorkerProperties(false));
    }

    private long unpublished() {
        return jdbc.sql("SELECT count(*) FROM platform.outbox WHERE published_at IS NULL").query(Long.class).single();
    }

    private KafkaConsumer<String, byte[]> consumerAtEnd() {
        return consumerAtEnd(TOPIC);
    }

    private KafkaConsumer<String, byte[]> consumerAtEnd(String topic) {
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Kafkas.broker().getBootstrapServers(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false), new StringDeserializer(), new ByteArrayDeserializer());
        try {
            TopicPartition partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToEnd(List.of(partition));
            consumer.position(partition, WAIT);
            return consumer;
        } catch (RuntimeException failure) {
            consumer.close(CloseOptions.timeout(Duration.ZERO));
            throw failure;
        }
    }

    private List<ConsumerRecord<String, byte[]>> receive(KafkaConsumer<String, byte[]> consumer, Set<UUID> expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<ConsumerRecord<String, byte[]>> received = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        while (!seen.containsAll(expected) && System.nanoTime() < deadline) {
            for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(100))) {
                UUID eventId = UUID.fromString(mapper.readTree(record.value()).get("event_id").asString());
                if (expected.contains(eventId)) {
                    seen.add(eventId);
                    received.add(record);
                }
            }
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
        return received;
    }

    record Verified(UUID driverId, String cityId, Instant verifiedAt, UUID verifiedBy) {
    }
}
package com.ridehailing.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.ridehailing.RideHailingApplication;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LocationIngestion;
import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.EventRecovery;
import com.ridehailing.platform.LogContext;
import com.ridehailing.platform.Outbox;
import com.ridehailing.support.Effects;
import com.ridehailing.support.Kafkas;
import com.ridehailing.support.Postgis;
import com.ridehailing.support.ScriptedConsumer;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

class KafkaRuntimeTests {

    private static final String OBSERVER = "test.kafka-runtime";
    private static final UUID DRIVER = UUID.fromString("0199a3f0-0000-7000-8000-000000000052");
    private static final UUID VEHICLE = UUID.fromString("0199a3f0-0000-7000-8000-000000000053");
        private static final String LOCATION_TOPIC = KafkaEventStream.LOCATION_TOPIC;
        private static final JsonMapper JSON = JsonMapper.builder().build();
        private static final Schema LOCATION_SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()))
            .getSchema(JSON.readTree(Path.of("docs/schemas/location/update.v1.json").toFile()));

    @Test
    void theApplicationPublishesAndConsumesThroughKafkaAndRestartsItsWorkers() throws Exception {
        try (KafkaContainer broker = Kafkas.newBroker()) {
            broker.start();
            KafkaProperties readOnly = new KafkaProperties(true, List.of(broker.getBootstrapServers()), Duration.ofSeconds(5),
                    false, 3, 1, (short) 1, 1);
            KafkaTopology missing = new KafkaTopology(readOnly, List.of());
            assertThatThrownBy(missing::prepare).isInstanceOf(IllegalStateException.class).hasMessageContaining("provision");
            try (Admin admin = admin(broker)) {
                assertThat(admin.listTopics().names().get(5, TimeUnit.SECONDS)).doesNotContainAnyElementsOf(KafkaTopics.eventTopics());
            }

            List<EventConsumer> consumers;
            try (ConfigurableApplicationContext application = new SpringApplicationBuilder(RideHailingApplication.class, Observer.class)
                    .profiles("test")
                    .run("--server.address=127.0.0.1", "--server.port=0", "--management.server.address=127.0.0.1",
                            "--management.server.port=0", "--spring.datasource.url=" + Postgis.database("kafka_runtime"),
                            "--spring.datasource.username=" + Postgis.shared().getUsername(),
                            "--spring.datasource.password=" + Postgis.shared().getPassword(), "--ride.roles=worker",
                            "--ride.location.single-process-check=false", "--ride.workers.autostart=true",
                            "--ride.kafka.enabled=true", "--ride.kafka.brokers=" + broker.getBootstrapServers(),
                            "--ride.kafka.manage-topics=true")) {
                JdbcClient jdbc = application.getBean(JdbcClient.class);
                Effects.reset(jdbc);
                assertThat(application.getBeansOfType(OutboxTransport.class)).hasSize(1);
                assertThat(application.getBean(OutboxTransport.class)).isInstanceOf(KafkaOutboxTransport.class);
                assertThat(application.getBeansOfType(InProcessOutboxTransport.class)).isEmpty();
                KafkaRuntime runtime = application.getBean(KafkaRuntime.class);
                OutboxRelay relay = application.getBean(OutboxRelay.class);
                assertThat(runtime.isRunning()).isTrue();
                assertThat(relay.isRunning()).isTrue();
                consumers = application.getBean(EventConsumers.class).all();
                assertThat(consumers).extracting(EventConsumer::name).contains("payment.charges", "payment.earnings",
                        "rating.windows", "notification.rides", "notification.drivers", "notification.payments", OBSERVER);

                UUID first = append(application, 1);
                awaitDelivery(jdbc, first);

                ScriptedConsumer observer = application.getBean("kafkaRuntimeObserver", ScriptedConsumer.class);
                observer.afterEffect(event -> {
                    throw new IllegalStateException("runtime poison event");
                });
                jdbc.sql("ALTER TABLE platform.dead_letters ADD CONSTRAINT test_mirror_unavailable CHECK (false) NOT VALID").update();
                UUID failed = append(application, 2);
                try (Admin admin = admin(broker)) {
                    String deadTopic = "drivers.events.dlq." + OBSERVER;
                    var partition = new org.apache.kafka.common.TopicPartition(deadTopic, 0);
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(admin.listOffsets(Map.of(
                            partition, org.apache.kafka.clients.admin.OffsetSpec.latest())).all().get(5, TimeUnit.SECONDS)
                            .get(partition).offset()).isEqualTo(1));
                    await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                        assertThat(jdbc.sql("SELECT count(*) FROM platform.dead_letters").query(Long.class).single()).isZero();
                        var mirrored = admin.listConsumerGroupOffsets(KafkaDeadLetterConsumer.GROUP)
                                .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                        assertThat(mirrored.values().stream().filter(Objects::nonNull).mapToLong(offset -> offset.offset()).sum()).isZero();
                    });
                } finally {
                    jdbc.sql("ALTER TABLE platform.dead_letters DROP CONSTRAINT test_mirror_unavailable").update();
                }
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertThat(jdbc.sql("SELECT event_id FROM platform.dead_letters WHERE consumer = :consumer")
                        .param("consumer", OBSERVER).query(UUID.class).optional()).contains(failed);
                    assertThat(jdbc.sql("SELECT error FROM platform.dead_letters WHERE consumer = :consumer")
                            .param("consumer", OBSERVER).query(String.class).single()).contains("runtime poison event");
                });
                assertThat(Effects.count(jdbc, OBSERVER, failed.toString())).isZero();
                observer.reset();
                application.stop();
                assertThat(runtime.isRunning()).isFalse();
                assertThat(relay.isRunning()).isFalse();

                EventRecovery recovery = application.getBean(EventRecovery.class);
                UUID replayId = recovery.startReplay(OBSERVER, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), false).id();
                assertThat(recovery.replay(replayId).totalEvents()).isEqualTo(2);
                UUID second = append(application, 3);
                assertThat(Effects.count(jdbc, OBSERVER, second.toString())).isZero();
                assertThat(jdbc.sql("SELECT published_at IS NULL FROM platform.outbox WHERE event_id = :id")
                        .param("id", second).query(Boolean.class).single()).isTrue();
                application.start();
                assertThat(runtime.isRunning()).isTrue();
                assertThat(relay.isRunning()).isTrue();
                awaitDelivery(jdbc, second);
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(recovery.replay(replayId).status()).isEqualTo("DONE"));
                assertThat(recovery.replay(replayId).events()).isEqualTo(2);
                assertThat(Effects.refs(jdbc, OBSERVER)).containsExactlyInAnyOrder(first.toString(), failed.toString(), second.toString());
                assertThat(jdbc.sql("SELECT count(*) FROM platform.dead_letters").query(Long.class).single()).isEqualTo(1);
            }

            KafkaTopology existing = new KafkaTopology(readOnly, consumers);
            existing.prepare();
            try (Admin admin = admin(broker)) {
                var offsets = admin.listConsumerGroupOffsets(OBSERVER).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                assertThat(offsets.values().stream().filter(Objects::nonNull).mapToLong(offset -> offset.offset()).sum()).isEqualTo(3);
                var mirrored = admin.listConsumerGroupOffsets(KafkaDeadLetterConsumer.GROUP).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS);
                assertThat(mirrored.values().stream().filter(Objects::nonNull).mapToLong(offset -> offset.offset()).sum()).isEqualTo(1);
                ConfigResource locations = new ConfigResource(ConfigResource.Type.TOPIC, LOCATION_TOPIC);
                for (String retention : List.of("1", "172800000", "-1")) {
                    admin.incrementalAlterConfigs(Map.of(locations, List.of(new AlterConfigOp(
                        new ConfigEntry(TopicConfig.RETENTION_MS_CONFIG, retention), AlterConfigOp.OpType.SET))))
                        .all().get(5, TimeUnit.SECONDS);
                    assertThatThrownBy(existing::prepare).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("retention").hasMessageContaining(LOCATION_TOPIC);
                    assertThat(admin.describeConfigs(List.of(locations)).all().get(5, TimeUnit.SECONDS).get(locations)
                        .get(TopicConfig.RETENTION_MS_CONFIG).value()).isEqualTo(retention);
                }
                admin.incrementalAlterConfigs(Map.of(locations, List.of(new AlterConfigOp(
                    new ConfigEntry(TopicConfig.RETENTION_MS_CONFIG, "86400000"), AlterConfigOp.OpType.SET))))
                    .all().get(5, TimeUnit.SECONDS);
                existing.prepare();
                ConfigResource rides = new ConfigResource(ConfigResource.Type.TOPIC, "rides.events");
                admin.incrementalAlterConfigs(Map.of(rides, List.of(new AlterConfigOp(
                        new ConfigEntry(TopicConfig.RETENTION_MS_CONFIG, "1"), AlterConfigOp.OpType.SET))))
                        .all().get(5, TimeUnit.SECONDS);
                assertThatThrownBy(existing::prepare).isInstanceOf(IllegalStateException.class).hasMessageContaining("retention");
                assertThat(admin.describeConfigs(List.of(rides)).all().get(5, TimeUnit.SECONDS).get(rides)
                        .get(TopicConfig.RETENTION_MS_CONFIG).value()).isEqualTo("1");
            }
        }
    }

    @Test
    void aRealBrokerOutageDoesNotBlockLocationsAndTheStreamResumesAfterRecoveryAndRestart() throws Exception {
        try (KafkaContainer broker = Kafkas.newBroker()) {
            broker.start();
            KafkaProperties properties = new KafkaProperties(true, List.of(broker.getBootstrapServers()), Duration.ofSeconds(5),
                    true, 1, 1, (short) 1, 1);
            new KafkaTopology(properties, List.of()).prepare();
            try (KafkaConsumer<String, byte[]> reader = new KafkaConsumer<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers(),
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                    ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5_000), new StringDeserializer(), new ByteArrayDeserializer());
                    ConfigurableApplicationContext application = new SpringApplicationBuilder(RideHailingApplication.class, TestCities.class)
                            .profiles("test")
                            .run("--server.address=127.0.0.1", "--server.port=0", "--management.server.address=127.0.0.1",
                                    "--management.server.port=0", "--spring.datasource.url=" + Postgis.database("kafka_locations"),
                                    "--spring.datasource.username=" + Postgis.shared().getUsername(),
                                    "--spring.datasource.password=" + Postgis.shared().getPassword(), "--ride.roles=api,realtime",
                                    "--ride.location.single-process-check=false", "--ride.workers.autostart=false",
                                    "--ride.kafka.enabled=true", "--ride.kafka.brokers=" + broker.getBootstrapServers(),
                                    "--ride.kafka.stream.queue-capacity=2", "--ride.kafka.stream.buffer-bytes=32768",
                                    "--ride.kafka.stream.send-timeout=500ms")) {
                reader.assign(List.of(new TopicPartition(LOCATION_TOPIC, 0)));
                reader.seekToBeginning(reader.assignment());
                TestCity city = application.getBean(TestCities.class).create("MINI");
                LiveIndex index = application.getBean(LiveIndex.class);
                index.mirror(city.id(), DRIVER, new LiveIndex.MirrorState(LiveIndex.Status.AVAILABLE, 1, "MINI", null));
                LocationIngestion ingestion = application.getBean(LocationIngestion.class);
                MeterRegistry metrics = application.getBean(MeterRegistry.class);
                KafkaEventStream stream = application.getBean(KafkaEventStream.class);
                AtomicLong sequence = new AtomicLong();
                assertThat(stream.isRunning()).isTrue();
                assertThat(application.getBeansOfType(KafkaRuntime.class)).isEmpty();
                assertLocationRecord(awaitPosition(reader, ingestion, city, sequence, 0), city);
                double failuresBefore = streamCount(metrics, "failed");

                broker.getDockerClient().pauseContainerCmd(broker.getContainerId()).exec();
                try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
                    callers.submit(() -> {
                        for (int update = 0; update < 200; update++) {
                            applyPosition(ingestion, city, sequence.incrementAndGet());
                        }
                    }).get(1, TimeUnit.SECONDS);
                    assertThat(index.position(city.id(), DRIVER)).hasValueSatisfying(position -> assertThat(position.seq()).isEqualTo(sequence.get()));
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(streamCount(metrics, "failed")).isGreaterThan(failuresBefore));
                    assertThat(streamCount(metrics, "queue_full")).isGreaterThan(0);
                } finally {
                    broker.getDockerClient().unpauseContainerCmd(broker.getContainerId()).exec();
                }

                long afterOutage = sequence.get();
                assertLocationRecord(awaitPosition(reader, ingestion, city, sequence, afterOutage), city);
                try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
                    String port = application.getEnvironment().getRequiredProperty("local.management.port");
                    var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus"))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.body()).contains("event_stream_records_total", "event_stream_queue_size",
                        "location_stream_failures_total", "outcome=\"queue_full\"", "outcome=\"failed\"");
                }
                application.stop();
                assertThat(stream.isRunning()).isFalse();
                assertThat(stream.offer(LOCATION_TOPIC, DRIVER, Map.of())).isFalse();
                application.start();
                assertThat(stream.isRunning()).isTrue();
                assertLocationRecord(awaitPosition(reader, ingestion, city, sequence, sequence.get()), city);
            }
        }
    }

    private static ConsumerRecord<String, byte[]> awaitPosition(KafkaConsumer<String, byte[]> reader,
            LocationIngestion ingestion, TestCity city, AtomicLong sequence, long after) {
        AtomicReference<ConsumerRecord<String, byte[]>> found = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).until(() -> {
            applyPosition(ingestion, city, sequence.incrementAndGet());
            for (ConsumerRecord<String, byte[]> record : reader.poll(Duration.ofMillis(100))) {
                if (JSON.readTree(record.value()).get("seq").asLong() > after) {
                    found.set(record);
                    return true;
                }
            }
            return false;
        });
        return found.get();
    }

    private static void applyPosition(LocationIngestion ingestion, TestCity city, long sequence) {
        assertThat(ingestion.live(city.id(), "MINI", DRIVER, new LiveIndex.LocationUpdate(sequence, city.at(0.1, 0.1),
                5, 90.0, 7.5, Instant.now()))).hasValueSatisfying(result ->
                        assertThat(result.outcome()).isEqualTo(LiveIndex.UpdateResult.Outcome.APPLIED));
    }

    private static void assertLocationRecord(ConsumerRecord<String, byte[]> record, TestCity city) {
        assertThat(record.topic()).isEqualTo(LOCATION_TOPIC);
        assertThat(record.key()).isEqualTo(DRIVER.toString());
        assertThat(new String(record.headers().lastHeader("schema_version").value(), StandardCharsets.UTF_8)).isEqualTo("1");
        var value = JSON.readTree(record.value());
        assertThat(LOCATION_SCHEMA.validate(value)).isEmpty();
        assertThat(value.get("driver_id").asString()).isEqualTo(record.key());
        assertThat(value.get("city_id").asString()).isEqualTo(city.id());
        assertThat(value.get("lat").asDouble()).isEqualTo(city.at(0.1, 0.1).lat());
        assertThat(value.get("lon").asDouble()).isEqualTo(city.at(0.1, 0.1).lon());
    }

    private static double streamCount(MeterRegistry metrics, String outcome) {
        return metrics.get("event_stream.records").tag("outcome", outcome).counter().count();
    }

    private static Admin admin(KafkaContainer broker) {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers()));
    }

    private UUID append(ConfigurableApplicationContext application, long version) {
        Outbox outbox = application.getBean(Outbox.class);
        TransactionTemplate transactions = application.getBean(TransactionTemplate.class);
        LogContext.run(Map.of(LogContext.ROLE, "worker"), () -> transactions.executeWithoutResult(transaction ->
                outbox.append(DomainEvent.of("DriverWentOnline", 1, "availability", DRIVER, version,
                        new WentOnline(DRIVER, "blr", "MINI", VEHICLE, Instant.now())))));
        return application.getBean(JdbcClient.class).sql("SELECT event_id FROM platform.outbox ORDER BY id DESC LIMIT 1")
                .query(UUID.class).single();
    }

    private void awaitDelivery(JdbcClient jdbc, UUID eventId) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            assertThat(Effects.count(jdbc, OBSERVER, eventId.toString())).isEqualTo(1);
            assertThat(jdbc.sql("SELECT published_at IS NOT NULL FROM platform.outbox WHERE event_id = :id")
                    .param("id", eventId).query(Boolean.class).single()).isTrue();
            assertThat(jdbc.sql("SELECT count(*) FROM platform.inbox WHERE consumer = :consumer AND event_id = :id")
                    .param("consumer", OBSERVER).param("id", eventId).query(Long.class).single()).isEqualTo(1);
        });
    }

    record WentOnline(UUID driverId, String cityId, String category, UUID vehicleId, Instant onlineAt) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Observer {

        @Bean
        ScriptedConsumer kafkaRuntimeObserver(JdbcClient jdbc) {
            return new ScriptedConsumer(OBSERVER, jdbc) {
                @Override
                public Set<String> eventTypes() {
                    return Set.of("DriverWentOnline");
                }
            };
        }
    }
}
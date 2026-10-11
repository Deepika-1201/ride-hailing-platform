package com.ridehailing.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ridehailing.RideHailingApplication;
import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.LogContext;
import com.ridehailing.platform.Outbox;
import com.ridehailing.support.Effects;
import com.ridehailing.support.Kafkas;
import com.ridehailing.support.Postgis;
import com.ridehailing.support.ScriptedConsumer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

class KafkaRuntimeTests {

    private static final String OBSERVER = "test.kafka-runtime";
    private static final UUID DRIVER = UUID.fromString("0199a3f0-0000-7000-8000-000000000052");
    private static final UUID VEHICLE = UUID.fromString("0199a3f0-0000-7000-8000-000000000053");

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
                application.stop();
                assertThat(runtime.isRunning()).isFalse();
                assertThat(relay.isRunning()).isFalse();

                UUID second = append(application, 2);
                assertThat(Effects.count(jdbc, OBSERVER, second.toString())).isZero();
                assertThat(jdbc.sql("SELECT published_at IS NULL FROM platform.outbox WHERE event_id = :id")
                        .param("id", second).query(Boolean.class).single()).isTrue();
                application.start();
                assertThat(runtime.isRunning()).isTrue();
                assertThat(relay.isRunning()).isTrue();
                awaitDelivery(jdbc, second);
                assertThat(Effects.refs(jdbc, OBSERVER)).containsExactly(first.toString(), second.toString());
            }

            KafkaTopology existing = new KafkaTopology(readOnly, consumers);
            existing.prepare();
            try (Admin admin = admin(broker)) {
                var offsets = admin.listConsumerGroupOffsets(OBSERVER).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                assertThat(offsets.values().stream().filter(Objects::nonNull).mapToLong(offset -> offset.offset()).sum()).isEqualTo(2);
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
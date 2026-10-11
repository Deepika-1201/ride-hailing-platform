package com.ridehailing.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.leases.LeaseProperties;
import com.ridehailing.platform.workers.WorkerProperties;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.SmartLifecycle;
import tools.jackson.databind.json.JsonMapper;

class KafkaConfigurationTests {

    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Properties.class);

        private final ApplicationContextRunner wiring = context
            .withUserConfiguration(KafkaConfiguration.class, InProcessOutboxTransport.class, EventConsumers.class)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withBean(ConsumerDelivery.class, () -> mock(ConsumerDelivery.class))
            .withPropertyValues("ride.workers.autostart=false");

    @Test
    void kafkaIsDisabledAndDoesNotAssumeALocalBroker() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            KafkaProperties properties = application.getBean(KafkaProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.brokers()).isEmpty();
            assertThat(properties.manageTopics()).isFalse();
            assertThat(properties.publishTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.partitions()).isEqualTo(3);
            assertThat(properties.deadLetterPartitions()).isEqualTo(1);
            assertThat(properties.replicationFactor()).isEqualTo((short) 1);
            assertThat(properties.minimumInSyncReplicas()).isEqualTo(1);
        });
    }

    @Test
    void enablingKafkaRequiresAnExplicitBroker() {
        context.withPropertyValues("ride.kafka.enabled=true").run(application -> assertThat(application).hasFailed());
        context.withPropertyValues("ride.kafka.enabled=true", "ride.kafka.brokers=localhost:9092")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBean(KafkaProperties.class).brokers()).containsExactly("localhost:9092");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"publish-timeout=0s", "publish-timeout=5ms", "publish-timeout=PT0.0055S", "publish-timeout=30d", "partitions=0",
        "dead-letter-partitions=0", "replication-factor=0", "minimum-in-sync-replicas=0", "minimum-in-sync-replicas=2"})
    void invalidKafkaSettingsFailDuringBinding(String property) {
        context.withPropertyValues("ride.kafka." + property).run(application -> assertThat(application).hasFailed());
    }

    @Test
    void theTopologyIncludesEveryEventTopicAndOneDeadLetterTopicPerGroupAndSource() {
        EventConsumer consumer = mock(EventConsumer.class);
        when(consumer.name()).thenReturn("notification.test");
        when(consumer.eventTypes()).thenReturn(Set.of("DriverVerified", "DriverSuspended", "ChargeFailed"));
        KafkaProperties properties = new KafkaProperties(true, List.of("localhost:9092"), Duration.ofSeconds(5),
                false, 3, 1, (short) 3, 2);

        List<NewTopic> topics = new KafkaTopology(properties, List.of(consumer)).topics();

        assertThat(topics).extracting(NewTopic::name).containsExactly("dispatch.events", "drivers.events",
                "drivers.events.dlq.notification.test", "payments.events", "payments.events.dlq.notification.test", "rides.events");
        assertThat(topics).allSatisfy(topic -> {
            boolean deadLetter = topic.name().contains(".dlq.");
            assertThat(topic.numPartitions()).isEqualTo(deadLetter ? 1 : 3);
            assertThat(topic.replicationFactor()).isEqualTo((short) 3);
            assertThat(topic.configs()).containsEntry(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2")
                    .containsEntry(TopicConfig.CLEANUP_POLICY_CONFIG, "delete")
                    .containsEntry(TopicConfig.UNCLEAN_LEADER_ELECTION_ENABLE_CONFIG, "false")
                    .containsEntry(TopicConfig.RETENTION_MS_CONFIG, Long.toString(Duration.ofDays(deadLetter ? 14 : 7).toMillis()));
        });
    }

    @Test
    void aFailedRuntimeStartClosesPartialResourcesAndARetryCreatesFreshConsumers() {
        KafkaTopology topology = mock(KafkaTopology.class);
        EventConsumers consumers = mock(EventConsumers.class);
        when(consumers.all()).thenReturn(List.of(mock(EventConsumer.class), mock(EventConsumer.class)));
        KafkaEventConsumer first = mock(KafkaEventConsumer.class);
        KafkaEventConsumer failed = mock(KafkaEventConsumer.class);
        KafkaEventConsumer freshFirst = mock(KafkaEventConsumer.class);
        KafkaEventConsumer freshSecond = mock(KafkaEventConsumer.class);
        doThrow(new IllegalStateException("listener startup failed")).when(failed).start();
        ArrayDeque<KafkaEventConsumer> instances = new ArrayDeque<>(List.of(first, failed, freshFirst, freshSecond));
        KafkaRuntime runtime = new KafkaRuntime(topology, consumers, ignored -> instances.removeFirst(), new WorkerProperties(false));

        assertThat(runtime.isAutoStartup()).isFalse();
        assertThat(runtime.getPhase()).isLessThan(SmartLifecycle.DEFAULT_PHASE);
        assertThatThrownBy(runtime::start).hasMessage("listener startup failed");
        assertThat(runtime.isRunning()).isFalse();
        verify(first).close();
        verify(failed).close();

        runtime.start();
        runtime.start();
        assertThat(runtime.isRunning()).isTrue();
        verify(freshFirst).start();
        verify(freshSecond).start();
        verify(topology, times(2)).prepare();
        runtime.stop();
        runtime.stop();
        assertThat(runtime.isRunning()).isFalse();
        verify(freshFirst).close();
        verify(freshSecond).close();
    }

    @Test
    void theDefaultContextSelectsOnlyInProcessDelivery() {
        wiring.run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(OutboxTransport.class)
                    .hasSingleBean(InProcessOutboxTransport.class).doesNotHaveBean(KafkaOutboxTransport.class)
                    .doesNotHaveBean(KafkaTopology.class).doesNotHaveBean(KafkaRuntime.class);
        });
    }

    @Test
    void kafkaSelectionAndWorkerLifecycleAreEnabledTogetherWithoutAutostart() {
        wiring.withPropertyValues("ride.kafka.enabled=true", "ride.kafka.brokers=127.0.0.1:1", "ride.roles=worker")
                .run(application -> {
                    assertThat(application).hasNotFailed().hasSingleBean(OutboxTransport.class)
                            .hasSingleBean(KafkaOutboxTransport.class).doesNotHaveBean(InProcessOutboxTransport.class)
                            .hasSingleBean(KafkaTopology.class).hasSingleBean(KafkaRuntime.class);
                    KafkaRuntime runtime = application.getBean(KafkaRuntime.class);
                    assertThat(runtime.isAutoStartup()).isFalse();
                    assertThat(runtime.isRunning()).isFalse();
                });
    }

    @Test
    void anApiOnlyProcessHasNoKafkaConsumerRuntimeEvenWithWorkerAutostartEnabled() {
        wiring.withPropertyValues("ride.kafka.enabled=true", "ride.kafka.brokers=127.0.0.1:1", "ride.roles=api",
                        "ride.workers.autostart=true")
                .run(application -> assertThat(application).hasNotFailed().hasSingleBean(KafkaOutboxTransport.class)
                        .doesNotHaveBean(KafkaTopology.class).doesNotHaveBean(KafkaRuntime.class));
    }

    @Test
    void publicationMustLeaveTimeForLeaseRenewal() {
        wiring.withPropertyValues("ride.kafka.enabled=true", "ride.kafka.brokers=127.0.0.1:1", "ride.kafka.publish-timeout=7s")
                .run(application -> {
                    assertThat(application).hasFailed();
                    assertThat(application.getStartupFailure()).hasRootCauseMessage(
                            "Kafka publish timeout plus lease renewal interval must be shorter than the lease TTL");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({KafkaProperties.class, LeaseProperties.class, WorkerProperties.class})
    static class Properties {
    }
}
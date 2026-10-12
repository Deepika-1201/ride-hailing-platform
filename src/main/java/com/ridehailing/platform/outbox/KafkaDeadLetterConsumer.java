package com.ridehailing.platform.outbox;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;

class KafkaDeadLetterConsumer implements AutoCloseable {

    static final String GROUP = "platform.dead-letters";

    private final KafkaMessageListenerContainer<byte[], byte[]> container;
    private boolean closed;

    KafkaDeadLetterConsumer(Map<String, String> topics, JdbcClient jdbc, JsonMapper json, List<String> brokers,
            Duration timeout) {
        if (topics.isEmpty()) {
            container = null;
            return;
        }
        DefaultKafkaConsumerFactory<byte[], byte[]> consumers = new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, List.copyOf(brokers),
                ConsumerConfig.GROUP_ID_CONFIG, GROUP,
                ConsumerConfig.CLIENT_ID_CONFIG, "ride-dead-letter-mirror",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false,
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100), new ByteArrayDeserializer(), new ByteArrayDeserializer());
        ContainerProperties properties = new ContainerProperties(topics.keySet().stream().sorted().toArray(String[]::new));
        properties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        properties.setSyncCommits(true);
        properties.setMissingTopicsFatal(true);
        properties.setPollTimeout(250);
        properties.setShutdownTimeout(timeout.toMillis() + 1_000);
        properties.setMessageListener(new KafkaDeadLetterListener(jdbc, json, topics));
        DefaultErrorHandler errors = new DefaultErrorHandler(new FixedBackOff(1_000, FixedBackOff.UNLIMITED_ATTEMPTS));
        errors.setClassifications(Map.of(), true);
        errors.setAckAfterHandle(false);
        container = new KafkaMessageListenerContainer<>(consumers, properties);
        container.setBeanName("kafka-dead-letter-mirror");
        container.setCommonErrorHandler(errors);
    }

    synchronized void start() {
        if (closed) {
            throw new IllegalStateException("Kafka dead-letter mirror is closed");
        }
        if (container != null) {
            container.start();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (container != null) {
            container.stop();
        }
    }
}
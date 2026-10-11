package com.ridehailing.platform.outbox;

import com.ridehailing.platform.EventConsumer;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;
import tools.jackson.databind.json.JsonMapper;

class KafkaEventConsumer implements AutoCloseable {

    private final KafkaMessageListenerContainer<String, byte[]> container;
    private final DefaultKafkaProducerFactory<String, byte[]> producers;
    private boolean closed;

    KafkaEventConsumer(EventConsumer consumer, ConsumerDelivery delivery, JsonMapper json, List<String> brokers,
            Duration timeout) {
        this(consumer, delivery, json, brokers, timeout, retryBackOff());
    }

    KafkaEventConsumer(EventConsumer consumer, ConsumerDelivery delivery, JsonMapper json, List<String> brokers,
            Duration timeout, BackOff retries) {
        Map<String, Object> producerProperties = new HashMap<>(KafkaOutboxTransport.producerProperties(brokers, timeout));
        int deliveryMillis = Math.toIntExact(timeout.toMillis());
        int lingerMillis = ((Number) producerProperties.get(ProducerConfig.LINGER_MS_CONFIG)).intValue();
        if (deliveryMillis <= lingerMillis) {
            throw new IllegalArgumentException("Dead-letter acknowledgement timeout must exceed the producer linger");
        }
        producerProperties.put(ProducerConfig.CLIENT_ID_CONFIG, "ride-dead-letter-" + consumer.name());
        producerProperties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryMillis);
        producerProperties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, deliveryMillis - lingerMillis);
        producers = new DefaultKafkaProducerFactory<>(producerProperties);
        producers.setPhysicalCloseTimeout(0);

        DeadLetterPublishingRecoverer recovery = new DeadLetterPublishingRecoverer(new KafkaTemplate<>(producers),
                (record, failure) -> new TopicPartition(record.topic() + ".dlq." + consumer.name(), -1));
        recovery.setVerifyPartition(false);
        recovery.setFailIfSendResultIsError(true);
        recovery.setTimeoutBuffer(0);
        recovery.setWaitForSendResultTimeout(timeout);
        DefaultErrorHandler errors = new DefaultErrorHandler(recovery, retries);
        errors.addNotRetryableExceptions(SerializationException.class);
        errors.setCommitRecovered(true);

        DefaultKafkaConsumerFactory<String, byte[]> consumers = new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, List.copyOf(brokers),
                ConsumerConfig.GROUP_ID_CONFIG, consumer.name(),
                ConsumerConfig.CLIENT_ID_CONFIG, "ride-events-" + consumer.name(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false,
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100), new StringDeserializer(), new ByteArrayDeserializer());
        String[] topics = consumer.eventTypes().stream().map(KafkaTopics::forEvent).distinct().sorted().toArray(String[]::new);
        ContainerProperties properties = new ContainerProperties(topics);
        properties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        properties.setSyncCommits(true);
        properties.setDeliveryAttemptHeader(true);
        properties.setMissingTopicsFatal(true);
        properties.setPollTimeout(250);
        properties.setShutdownTimeout(timeout.toMillis() + 1_000);
        properties.setMessageListener(new KafkaEventListener(consumer, delivery, json));
        container = new KafkaMessageListenerContainer<>(consumers, properties);
        container.setBeanName("kafka-events-" + consumer.name());
        container.setCommonErrorHandler(errors);
    }

    static BackOff retryBackOff() {
        ExponentialBackOffWithMaxRetries retries = new ExponentialBackOffWithMaxRetries(3);
        retries.setInitialInterval(1_000);
        retries.setMultiplier(2);
        retries.setMaxInterval(4_000);
        return retries;
    }

    synchronized void start() {
        if (closed) {
            throw new IllegalStateException("Kafka event consumer is closed");
        }
        container.start();
    }

    @Override
    public synchronized void close() {
        closed = true;
        try {
            container.stop();
        } finally {
            producers.destroy();
        }
    }
}
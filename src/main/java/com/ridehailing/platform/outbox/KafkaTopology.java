package com.ridehailing.platform.outbox;

import com.ridehailing.platform.EventConsumer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.TopicExistsException;

class KafkaTopology {

    private final KafkaProperties properties;
    private final List<NewTopic> topics;
    private final Map<String, String> deadLetterTopics;

    KafkaTopology(KafkaProperties properties, List<EventConsumer> consumers) {
        this.properties = properties;
        Map<String, NewTopic> planned = new TreeMap<>();
        Map<String, String> mirrored = new TreeMap<>();
        KafkaTopics.eventTopics().forEach(topic -> planned.put(topic, topic(topic, properties.partitions(), 7)));
        for (EventConsumer consumer : consumers) {
            if (consumer.name() == null || !consumer.name().matches("[A-Za-z0-9._-]+") || consumer.eventTypes().isEmpty()) {
                throw new IllegalArgumentException("Kafka consumer needs a valid name and at least one event type");
            }
            for (String eventType : consumer.eventTypes()) {
                String name = KafkaTopics.forEvent(eventType) + ".dlq." + consumer.name();
                if (name.length() > 249) {
                    throw new IllegalArgumentException("Kafka dead-letter topic name is too long");
                }
                planned.put(name, topic(name, properties.deadLetterPartitions(), 14));
                mirrored.put(name, consumer.name());
            }
        }
        topics = List.copyOf(planned.values());
        deadLetterTopics = Map.copyOf(mirrored);
    }

    List<NewTopic> topics() {
        return topics;
    }

    Map<String, String> deadLetterTopics() {
        return deadLetterTopics;
    }

    void prepare() {
        int timeout = Math.toIntExact(properties.publishTimeout().toMillis());
        Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, properties.brokers(),
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeout,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeout));
        try {
            if (properties.manageTopics()) {
                var created = admin.createTopics(topics).values();
                for (var result : created.values()) {
                    try {
                        result.get(timeout, TimeUnit.MILLISECONDS);
                    } catch (ExecutionException failure) {
                        if (!(failure.getCause() instanceof TopicExistsException)) {
                            throw failure;
                        }
                    }
                }
            }
            Map<String, TopicDescription> descriptions = admin.describeTopics(topics.stream().map(NewTopic::name).toList())
                    .allTopicNames().get(timeout, TimeUnit.MILLISECONDS);
            List<ConfigResource> resources = topics.stream()
                    .map(topic -> new ConfigResource(ConfigResource.Type.TOPIC, topic.name())).toList();
            Map<ConfigResource, Config> configurations = admin.describeConfigs(resources).all()
                    .get(timeout, TimeUnit.MILLISECONDS);
            for (NewTopic expected : topics) {
                TopicDescription actual = descriptions.get(expected.name());
                if (actual.partitions().size() < expected.numPartitions()
                        || actual.partitions().stream().anyMatch(partition -> partition.replicas().size() < expected.replicationFactor())) {
                    throw new IllegalStateException("Kafka topic has insufficient partitions or replicas: " + expected.name());
                }
                Config configuration = configurations.get(new ConfigResource(ConfigResource.Type.TOPIC, expected.name()));
                for (var setting : expected.configs().entrySet()) {
                    String actualValue = configuration.get(setting.getKey()).value();
                    if (setting.getKey().equals(TopicConfig.RETENTION_MS_CONFIG)) {
                        long retention = Long.parseLong(actualValue);
                        if (retention != -1 && retention < Long.parseLong(setting.getValue())) {
                            throw new IllegalStateException("Kafka topic retention is too short: " + expected.name());
                        }
                    } else if (!setting.getValue().equals(actualValue)) {
                        throw new IllegalStateException("Kafka topic " + expected.name() + " has incompatible " + setting.getKey());
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted preparing Kafka topics", interrupted);
        } catch (ExecutionException | TimeoutException failed) {
            throw new IllegalStateException("Kafka topics could not be prepared; provision them or explicitly enable topic management", failed);
        } finally {
            admin.close(Duration.ZERO);
        }
    }

    private NewTopic topic(String name, int partitions, int retentionDays) {
        return new NewTopic(name, partitions, properties.replicationFactor()).configs(Map.of(
                TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, Integer.toString(properties.minimumInSyncReplicas()),
                TopicConfig.UNCLEAN_LEADER_ELECTION_ENABLE_CONFIG, "false",
                TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE,
                TopicConfig.RETENTION_MS_CONFIG, Long.toString(Duration.ofDays(retentionDays).toMillis())));
    }
}
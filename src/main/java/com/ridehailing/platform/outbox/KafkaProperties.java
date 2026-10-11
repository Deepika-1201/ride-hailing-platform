package com.ridehailing.platform.outbox;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ride.kafka")
public record KafkaProperties(
        @DefaultValue("false") boolean enabled,
        List<String> brokers,
        @DefaultValue("5s") Duration publishTimeout,
        @DefaultValue("false") boolean manageTopics,
        @DefaultValue("3") int partitions,
        @DefaultValue("1") int deadLetterPartitions,
        @DefaultValue("1") short replicationFactor,
        @DefaultValue("1") int minimumInSyncReplicas) {

    public KafkaProperties {
        brokers = brokers == null ? List.of() : List.copyOf(brokers);
        if (enabled && brokers.isEmpty() || brokers.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Enabled Kafka requires explicit, nonblank brokers");
        }
        if (publishTimeout == null || publishTimeout.toMillis() <= 5
                || publishTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Kafka publish timeout must exceed 5ms and fit client millisecond limits");
        }
        if (partitions < 1 || deadLetterPartitions < 1 || replicationFactor < 1
                || minimumInSyncReplicas < 1 || minimumInSyncReplicas > replicationFactor) {
            throw new IllegalArgumentException("Kafka partition and replica settings must be positive and consistent");
        }
    }
}
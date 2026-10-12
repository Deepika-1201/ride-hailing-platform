package com.ridehailing.platform.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ride.kafka.stream")
public record KafkaStreamProperties(
        @DefaultValue("1024") int queueCapacity,
        @DefaultValue("2048") int maxRecordBytes,
        @DefaultValue("1048576") long bufferBytes,
        @DefaultValue("2s") Duration sendTimeout,
        @DefaultValue("1s") Duration shutdownTimeout) {

    public KafkaStreamProperties {
        if (queueCapacity < 1 || queueCapacity > 100_000 || maxRecordBytes < 128 || maxRecordBytes > 1_048_576
                || bufferBytes < Math.max(32_768, maxRecordBytes + 1_024L) || bufferBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Kafka stream queue, record and producer buffer bounds must be positive and consistent");
        }
        if (sendTimeout == null || sendTimeout.toMillis() <= 20 || sendTimeout.toMillis() > Integer.MAX_VALUE
                || shutdownTimeout == null || shutdownTimeout.toMillis() < 1 || shutdownTimeout.toMillis() > 10_000) {
            throw new IllegalArgumentException("Kafka stream send timeout must exceed 20ms; shutdown must be between 1ms and 10s");
        }
    }
}
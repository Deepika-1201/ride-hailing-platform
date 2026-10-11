package com.ridehailing.support;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

public final class Kafkas {

    private static KafkaContainer broker;

    private Kafkas() {
    }

    public static KafkaContainer newBroker() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
                .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m")
                .withEnv("KAFKA_LOG_CLEANER_DEDUPE_BUFFER_SIZE", "16777216");
    }

    public static synchronized KafkaContainer broker() {
        if (broker == null) {
            KafkaContainer candidate = newBroker();
            try {
                candidate.start();
                broker = candidate;
            } catch (RuntimeException failure) {
                candidate.stop();
                throw failure;
            }
        }
        return broker;
    }
}
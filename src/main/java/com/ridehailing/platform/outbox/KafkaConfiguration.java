package com.ridehailing.platform.outbox;

import com.ridehailing.platform.ConditionalOnRole;
import com.ridehailing.platform.Role;
import com.ridehailing.platform.leases.LeaseProperties;
import com.ridehailing.platform.workers.WorkerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ride.kafka", name = "enabled", havingValue = "true")
class KafkaConfiguration {

    @Bean(destroyMethod = "close")
    KafkaOutboxTransport kafkaOutboxTransport(JsonMapper json, KafkaProperties properties, LeaseProperties leases) {
        if (properties.publishTimeout().plus(leases.renewEvery()).compareTo(leases.ttl()) >= 0) {
            throw new IllegalArgumentException("Kafka publish timeout plus lease renewal interval must be shorter than the lease TTL");
        }
        return new KafkaOutboxTransport(json, properties.brokers(), properties.publishTimeout());
    }

    @Bean
    @ConditionalOnRole(Role.WORKER)
    KafkaTopology kafkaTopology(KafkaProperties properties, EventConsumers consumers) {
        return new KafkaTopology(properties, consumers.all());
    }

    @Bean
    @ConditionalOnRole(Role.WORKER)
    KafkaRuntime kafkaRuntime(KafkaTopology topology, EventConsumers consumers, ConsumerDelivery delivery, JsonMapper json,
            KafkaProperties properties, WorkerProperties workers) {
        return new KafkaRuntime(topology, consumers,
                consumer -> new KafkaEventConsumer(consumer, delivery, json, properties.brokers(), properties.publishTimeout()), workers);
    }
}
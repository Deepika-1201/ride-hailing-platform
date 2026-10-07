package com.ridehailing.platform.valkey;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Valkey connection exists only when the stores live there (LLD §1.3); it connects at startup or fails it. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ride.location.store", havingValue = "valkey")
class ValkeyConfiguration {

    @Bean(destroyMethod = "close")
    LettuceValkey valkey(ValkeyProperties properties, MeterRegistry meters) {
        return LettuceValkey.connect(properties, meters);
    }
}

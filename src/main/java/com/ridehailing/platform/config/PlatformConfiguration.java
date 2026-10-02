package com.ridehailing.platform.config;

import com.ridehailing.platform.InstanceId;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PlatformConfiguration {

    /** UTC in microseconds, the precision of {@code timestamptz}, so values read back equal values written. */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    @Bean
    InstanceId instanceId() {
        return InstanceId.generate();
    }
}

package com.ridehailing.platform.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** How long keys are kept, and how long a call waits for another call holding its key (LLD §5.1). */
@ConfigurationProperties(prefix = "ride.idempotency")
public record IdempotencyProperties(@DefaultValue("24h") Duration ttl, @DefaultValue("1s") Duration lockTimeout) {
}

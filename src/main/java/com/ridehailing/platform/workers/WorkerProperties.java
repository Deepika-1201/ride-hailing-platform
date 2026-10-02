package com.ridehailing.platform.workers;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code autostart=false} keeps background loops stopped, so tests can drive them step by step (LLD §1.3). */
@ConfigurationProperties(prefix = "ride.workers")
public record WorkerProperties(@DefaultValue("true") boolean autostart) {
}

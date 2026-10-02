package com.ridehailing.identity.app;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Refresh tokens' lifetime, and the window in which reusing a rotated one counts as a retry (LLD §12.2). */
@ConfigurationProperties(prefix = "ride.security.jwt")
public record SessionProperties(
        @DefaultValue("30d") Duration refreshTtl,
        @DefaultValue("10s") Duration refreshReuseGrace) {
}

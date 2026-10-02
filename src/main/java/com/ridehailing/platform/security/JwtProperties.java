package com.ridehailing.platform.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Access-token settings (LLD §12.2, §12.3). {@code keys} are EC P-256 private keys as JWK JSON with a {@code kid};
 * the first signs and all verify.
 */
@ConfigurationProperties(prefix = "ride.security.jwt")
public record JwtProperties(
        @DefaultValue("ride-hailing") String issuer,
        @DefaultValue("15m") Duration accessTtl,
        List<String> keys) {

    public static final String AUDIENCE = "ride-api";

    public JwtProperties {
        keys = keys == null ? List.of() : List.copyOf(keys);
    }
}

package com.ridehailing.identity.app;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * One-time codes (LLD §12.1). {@code fixedCode} and a generated {@code hmacSecret} are allowed only in the local and
 * test profiles.
 */
@ConfigurationProperties(prefix = "ride.security.otp")
public record SignInProperties(
        @DefaultValue("5m") Duration ttl,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("30s") Duration resendAfter,
        String fixedCode,
        String hmacSecret) {
}

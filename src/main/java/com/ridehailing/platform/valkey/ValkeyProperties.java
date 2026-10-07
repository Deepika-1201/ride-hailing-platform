package com.ridehailing.platform.valkey;

import com.ridehailing.platform.Valkey;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code ride.valkey} (ADR-020, LLD §20): where Valkey is, whether it is a cluster (the URI is then a seed node), and
 * how long each kind of call may wait.
 */
@ConfigurationProperties("ride.valkey")
public record ValkeyProperties(
        @DefaultValue("redis://localhost:6379") String uri,
        @DefaultValue("false") boolean cluster,
        @DefaultValue Timeouts timeouts) {

    public record Timeouts(
            @DefaultValue("50ms") Duration query,
            @DefaultValue("50ms") Duration mirror,
            @DefaultValue("100ms") Duration update,
            @DefaultValue("100ms") Duration other) {

        Valkey.Timeouts toValkey() {
            return new Valkey.Timeouts(query, mirror, update, other);
        }
    }
}

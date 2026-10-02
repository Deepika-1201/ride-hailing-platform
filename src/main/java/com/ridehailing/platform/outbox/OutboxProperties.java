package com.ridehailing.platform.outbox;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The relay's idle poll and batch size, and the delays between a failing consumer's retries (LLD §5.2, §5.3). */
@ConfigurationProperties(prefix = "ride.outbox")
public record OutboxProperties(
        @DefaultValue("100ms") Duration idlePoll,
        @DefaultValue("500") int batchSize,
        @DefaultValue({"500ms", "1s", "2s"}) List<Duration> retryDelays) {
}

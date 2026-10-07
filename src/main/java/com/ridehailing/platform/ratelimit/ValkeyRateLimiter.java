package com.ridehailing.platform.ratelimit;

import com.ridehailing.platform.RateLimiter;
import com.ridehailing.platform.Valkey;
import com.ridehailing.platform.Valkey.Script;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * V2's limiter (LLD §5.8): one script call per request on {@code rl:<limit>:<key>}, so limits hold across nodes.
 * Without Valkey a limit allows, unless it fails closed, as sign-in's do.
 */
@Component
@ConditionalOnProperty(name = "ride.location.store", havingValue = "valkey")
class ValkeyRateLimiter implements RateLimiter {

    static final Script SCRIPT = Script.named("rate_limit");
    /** When a limit that fails closed can't reach Valkey. */
    static final Duration CLOSED_RETRY = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(ValkeyRateLimiter.class);

    private final RateLimitProperties properties;
    private final Valkey valkey;
    private final Clock clock;

    ValkeyRateLimiter(RateLimitProperties properties, Valkey valkey, Clock clock) {
        this.properties = properties;
        this.valkey = valkey;
        this.clock = clock;
        valkey.load(SCRIPT);
    }

    @Override
    public RateDecision tryAcquire(String limit, String key) {
        RateLimitProperties.Limit configured = properties.named(limit);
        List<Long> reply;
        try {
            reply = valkey.run(SCRIPT, ScriptOutputType.MULTI, valkey.timeouts().other(),
                    new String[] {"rl:" + limit + ":" + key}, Integer.toString(configured.capacity()),
                    Long.toString(configured.period().toMillis()), Long.toString(clock.millis()));
        } catch (RedisException e) {
            log.warn("Rate limit {} couldn't reach Valkey, so it {}: {}", limit,
                    configured.failClosed() ? "rejects" : "allows", e.toString());
            return configured.failClosed() ? new RateDecision(false, CLOSED_RETRY) : RateDecision.ALLOWED;
        }
        return reply.get(0) == 1 ? RateDecision.ALLOWED : new RateDecision(false, Duration.ofMillis(reply.get(1)));
    }
}

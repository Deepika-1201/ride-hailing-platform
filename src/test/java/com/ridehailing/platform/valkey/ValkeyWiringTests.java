package com.ridehailing.platform.valkey;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.identity.tickets.TicketStore;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.index.ValkeyLiveIndex;
import com.ridehailing.platform.RateLimiter;
import com.ridehailing.platform.ratelimit.RateLimitProperties;
import com.ridehailing.support.ValkeyIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** LLD §1.3: {@code ride.location.store=valkey} puts the live index, the rate limits and the tickets in Valkey. */
class ValkeyWiringTests extends ValkeyIntegrationTest {

    @Autowired
    private LiveIndex index;

    @Autowired
    private RateLimiter limiter;

    @Autowired
    private TicketStore tickets;

    @Autowired
    private RateLimitProperties limits;

    @Test
    void theStoresLiveInValkey() {
        assertThat(index).isInstanceOf(ValkeyLiveIndex.class);
        assertThat(limiter.getClass().getSimpleName()).isEqualTo("ValkeyRateLimiter");
        assertThat(tickets.getClass().getSimpleName()).isEqualTo("ValkeyTicketStore");
    }

    @Test
    void onlySignInsLimitsFailClosed() {
        assertThat(limits.rateLimits()).allSatisfy((name, limit) ->
                assertThat(limit.failClosed()).as(name).isEqualTo(name.startsWith("otp-")));
        assertThat(limits.rateLimits()).containsKeys("otp-per-phone", "otp-per-ip");
    }
}

package com.ridehailing.platform.leases;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.Leases;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.PlatformTables;
import java.time.Duration;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** LLD §5.5: one holder at a time, takeover after expiry, and a fencing token that grows with every acquisition. */
class LeasesTests extends IntegrationTest {

    private static final String LEASE = "test-lease";
    private static final Duration TTL = Duration.ofSeconds(10);

    @Autowired
    private Leases leases;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        PlatformTables.reset(jdbc);
    }

    @Test
    void theFirstAcquisitionCreatesTheLeaseWithToken1() {
        assertThat(leases.acquire(LEASE, "node-a", TTL)).hasValue(1);
    }

    @Test
    void aHeldLeaseCannotBeTakenEvenByItsHolder() {
        leases.acquire(LEASE, "node-a", TTL);

        assertThat(leases.acquire(LEASE, "node-b", TTL)).isEmpty();
        assertThat(leases.acquire(LEASE, "node-a", TTL)).isEmpty();
    }

    @Test
    void theHolderRenewsWithItsToken() {
        long token = leases.acquire(LEASE, "node-a", TTL).orElseThrow();

        assertThat(leases.renew(LEASE, "node-a", token, TTL)).isTrue();
        assertThat(leases.renew(LEASE, "node-b", token, TTL)).isFalse();
        assertThat(leases.renew(LEASE, "node-a", token + 1, TTL)).isFalse();
    }

    @Test
    void anExpiredLeaseIsTakenOverWithANewTokenAndTheOldHolderCannotRenew() {
        long first = leases.acquire(LEASE, "node-a", TTL).orElseThrow();
        expire(LEASE);

        OptionalLong second = leases.acquire(LEASE, "node-b", TTL);

        assertThat(second).hasValue(first + 1);
        assertThat(leases.renew(LEASE, "node-a", first, TTL)).isFalse();
        assertThat(leases.renew(LEASE, "node-b", second.getAsLong(), TTL)).isTrue();
    }

    @Test
    void anExpiredLeaseCannotBeRenewedEvenWithoutATakeover() {
        long token = leases.acquire(LEASE, "node-a", TTL).orElseThrow();
        expire(LEASE);

        assertThat(leases.renew(LEASE, "node-a", token, TTL)).isFalse();
    }

    @Test
    void aReleasedLeaseIsFreeAtOnce() {
        long token = leases.acquire(LEASE, "node-a", TTL).orElseThrow();
        leases.release(LEASE, "node-b", token);
        assertThat(leases.acquire(LEASE, "node-b", TTL)).isEmpty();

        leases.release(LEASE, "node-a", token);

        assertThat(leases.acquire(LEASE, "node-b", TTL)).hasValue(token + 1);
    }

    private void expire(String lease) {
        jdbc.sql("UPDATE platform.leases SET expires_at = now() - interval '1 second' WHERE name = :name")
                .param("name", lease)
                .update();
    }
}

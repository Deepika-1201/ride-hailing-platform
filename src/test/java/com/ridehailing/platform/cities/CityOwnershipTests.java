package com.ridehailing.platform.cities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ridehailing.platform.CityOwnership;
import com.ridehailing.platform.InstanceId;
import com.ridehailing.platform.Leases;
import com.ridehailing.platform.LogContext;
import com.ridehailing.platform.workers.WorkerProperties;
import com.ridehailing.support.Effects;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.PlatformTables;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

class CityOwnershipTests extends IntegrationTest {

    private static final Duration TTL = Duration.ofSeconds(10);
    private static final CityOwnershipProperties PROPERTIES = new CityOwnershipProperties(true, Set.of("blr", "mum"),
            TTL, Duration.ofMillis(100));

        private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Properties.class, CityLeaseCoordinator.class)
            .withBean(Leases.class, () -> this.leases)
            .withBean(InstanceId.class, () -> new InstanceId("configuration-test"))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("ride.roles=dispatch", "ride.workers.autostart=false");

    @Autowired
    Leases leases;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void reset() {
        PlatformTables.reset(jdbc);
        Effects.reset(jdbc);
    }

    @Test
    void citiesHaveIndependentOwnersAndStaleWorkCannotCommitAfterTakeover() {
        CityLeaseCoordinator first = coordinator(leases, "node-a");
        CityLeaseCoordinator second = coordinator(leases, "node-b");
        try {
            first.maintain();
            second.maintain();
            assertThat(first.owned()).extracting(claim -> claim.cityId()).containsExactly("blr", "mum");
            assertThat(second.owned()).isEmpty();
            var original = first.owned().getFirst();
            assertThat(first.runIfOwned(original, () -> {
                assertThat(MDC.get(LogContext.ROLE)).isEqualTo("dispatch");
                Effects.record(jdbc, "city-owner", "before");
            })).isTrue();
            expire("blr");
            second.maintain();
            assertThat(second.owned()).singleElement().satisfies(claim -> assertThat(claim.token()).isGreaterThan(original.token()));
            assertThat(first.runIfOwned(original, () -> Effects.record(jdbc, "city-owner", "stale"))).isFalse();
            first.maintain();
            assertThat(first.owned()).extracting(claim -> claim.cityId()).containsExactly("mum");
            assertThat(second.runIfOwned(second.owned().getFirst(), () -> Effects.record(jdbc, "city-owner", "after"))).isTrue();
            assertThat(Effects.refs(jdbc, "city-owner")).containsExactly("before", "after");
        } finally {
            first.stop();
            second.stop();
        }
    }

    @Test
    void losingOwnershipDuringWorkRollsBackAndDoesNotAffectAnotherCity() {
        CityLeaseCoordinator owner = coordinator(leases, "node-a");
        try {
            owner.maintain();
            var claim = owner.owned().getFirst();
            assertThat(owner.runIfOwned(claim, () -> {
                Effects.record(jdbc, "city-owner", "expired");
                expire(claim.cityId());
            })).isFalse();
            assertThat(owner.owned()).extracting(held -> held.cityId()).containsExactly("mum");
            assertThat(Effects.refs(jdbc, "city-owner")).isEmpty();
        } finally {
            owner.stop();
        }
    }

    @Test
    void shutdownReleasesClaimsAndRestartUsesNewTokens() {
        CityLeaseCoordinator owner = coordinator(leases, "node-a");
        owner.maintain();
        var old = owner.owned().getFirst();
        owner.stop();
        assertThat(owner.owned()).isEmpty();
        owner.maintain();
        assertThat(owner.owned()).isEmpty();
        assertThat(owner.runIfOwned(old, () -> { throw new AssertionError("stopped owner ran work"); })).isFalse();
        try {
            owner.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(owner.owned()).hasSize(2);
                assertThat(owner.owned().getFirst().token()).isGreaterThan(old.token());
            });
            assertThat(owner.isRunning()).isTrue();
            assertThat(owner.isAutoStartup()).isFalse();
        } finally {
            owner.stop();
        }
    }

    @Test
    void renewalFailureClearsTheLocalClaim() {
        Leases unavailable = mock(Leases.class);
        when(unavailable.acquire("city-owner:blr", "node-a", TTL)).thenReturn(OptionalLong.of(1));
        when(unavailable.acquire("city-owner:mum", "node-a", TTL)).thenReturn(OptionalLong.empty());
        when(unavailable.renew("city-owner:blr", "node-a", 1, TTL)).thenThrow(new DataAccessResourceFailureException("database unavailable"));
        CityLeaseCoordinator owner = coordinator(unavailable, "node-a");
        owner.maintain();
        assertThat(owner.owned()).hasSize(1);
        owner.maintain();
        assertThat(owner.owned()).isEmpty();
        owner.stop();
    }

    @Test
    void anAcquisitionCompletingAfterShutdownIsReleasedRatherThanPublished() throws Exception {
        Leases blocked = mock(Leases.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(blocked.acquire("city-owner:blr", "node-a", TTL)).thenAnswer(invocation -> {
            entered.countDown();
            if (!finish.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("acquisition gate timed out");
            }
            return OptionalLong.of(7);
        });
        CityLeaseCoordinator owner = coordinator(blocked, "node-a");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var acquiring = executor.submit(owner::maintain);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                owner.stop();
            } finally {
                finish.countDown();
            }
            acquiring.get(5, TimeUnit.SECONDS);
        }
        assertThat(owner.owned()).isEmpty();
        verify(blocked).release("city-owner:blr", "node-a", 7);
    }

    @Test
    void businessFailureRollsBackWithoutDiscardingValidOwnership() {
        CityLeaseCoordinator owner = coordinator(leases, "node-a");
        try {
            owner.maintain();
            var claim = owner.owned().getFirst();
            assertThatThrownBy(() -> owner.runIfOwned(claim, () -> {
                Effects.record(jdbc, "city-owner", "failed");
                throw new IllegalStateException("business work failed");
            })).hasMessage("business work failed");
            assertThat(owner.owned()).contains(claim);
            assertThat(Effects.refs(jdbc, "city-owner")).isEmpty();
        } finally {
            owner.stop();
        }
    }

    @Test
    void shutdownDuringGuardedWorkRollsBackBeforeAnotherOwnerTakesOver() throws Exception {
        CityLeaseCoordinator owner = coordinator(leases, "node-a");
        CityLeaseCoordinator replacement = coordinator(leases, "node-b");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            owner.maintain();
            var claim = owner.owned().getFirst();
            var working = executor.submit(() -> owner.runIfOwned(claim, () -> {
                Effects.record(jdbc, "city-owner", "stopped");
                entered.countDown();
                awaitGate(finish);
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var stopping = executor.submit(() -> owner.stop());
                await().atMost(Duration.ofSeconds(3)).until(() -> owner.owned().isEmpty());
                finish.countDown();
                assertThat(working.get(5, TimeUnit.SECONDS)).isFalse();
                stopping.get(5, TimeUnit.SECONDS);
                replacement.maintain();
                assertThat(replacement.owned()).hasSize(2);
                assertThat(replacement.owned().getFirst().token()).isGreaterThan(claim.token());
                assertThat(Effects.refs(jdbc, "city-owner")).isEmpty();
            } finally {
                finish.countDown();
            }
        } finally {
            owner.stop();
            replacement.stop();
        }
    }

    @Test
    void shutdownDoesNotWaitIndefinitelyForLeaseRelease() throws Exception {
        Leases blocked = mock(Leases.class);
        when(blocked.acquire("city-owner:blr", "node-a", TTL)).thenReturn(OptionalLong.of(1));
        when(blocked.acquire("city-owner:mum", "node-a", TTL)).thenReturn(OptionalLong.of(2));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            try {
                finish.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                released.countDown();
            }
            return null;
        }).when(blocked).release(anyString(), eq("node-a"), anyLong());
        CityLeaseCoordinator owner = coordinator(blocked, "node-a");
        owner.maintain();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stopping = executor.submit(() -> owner.stop());
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                stopping.get(7, TimeUnit.SECONDS);
                assertThat(owner.owned()).isEmpty();
                assertThat(released.await(2, TimeUnit.SECONDS)).isTrue();
            } finally {
                finish.countDown();
            }
        }
        verify(blocked, times(1)).release(anyString(), eq("node-a"), anyLong());
    }

    @Test
    void configurationDefaultsDisableOwnershipAndAutostartCanBeSuppressed() {
        context.run(application -> {
            assertThat(application).hasNotFailed().doesNotHaveBean(CityOwnership.class);
            CityOwnershipProperties defaults = application.getBean(CityOwnershipProperties.class);
            assertThat(defaults.enabled()).isFalse();
            assertThat(defaults.cities()).isEmpty();
            assertThat(defaults.ttl()).isEqualTo(Duration.ofSeconds(10));
            assertThat(defaults.renewEvery()).isEqualTo(Duration.ofSeconds(3));
        });
        context.withPropertyValues("ride.dispatch.city-ownership.enabled=true", "ride.dispatch.city-ownership.cities=blr,mum")
                .run(application -> {
                    assertThat(application).hasNotFailed().hasSingleBean(CityOwnership.class);
                    CityLeaseCoordinator owner = application.getBean(CityLeaseCoordinator.class);
                    assertThat(owner.isAutoStartup()).isFalse();
                    assertThat(owner.isRunning()).isFalse();
                    assertThat(owner.owned()).isEmpty();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "worker", "realtime", "api,worker,realtime"})
    void otherRolesNeverStartCityOwnership(String roles) {
        context.withPropertyValues("ride.roles=" + roles, "ride.workers.autostart=true",
                        "ride.dispatch.city-ownership.enabled=true", "ride.dispatch.city-ownership.cities=blr")
                .run(application -> assertThat(application).hasNotFailed().doesNotHaveBean(CityOwnership.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cities=", "cities=BLR", "cities=blr/mum", "ttl=99ms", "ttl=301s",
        "renew-every=9ms", "renew-every=5s", "renew-every=30d"})
    void invalidOwnershipSettingsFailDuringBinding(String setting) {
        context.withPropertyValues("ride.dispatch.city-ownership.enabled=true", "ride.dispatch.city-ownership.cities=blr",
                        "ride.dispatch.city-ownership." + setting)
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void anAutostartedContextAcquiresAndRenewsAndCanRestartWithNewTokens() {
        context.withPropertyValues("ride.workers.autostart=true", "ride.dispatch.city-ownership.enabled=true",
                        "ride.dispatch.city-ownership.cities=blr", "ride.dispatch.city-ownership.renew-every=100ms")
                .run(application -> {
                    assertThat(application).hasNotFailed().hasSingleBean(CityOwnership.class);
                    CityLeaseCoordinator owner = application.getBean(CityLeaseCoordinator.class);
                    MeterRegistry metrics = application.getBean(MeterRegistry.class);
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                        assertThat(owner.owned()).hasSize(1);
                        assertThat(metrics.get("dispatch.city.ownership").tag("outcome", "renewed").counter().count()).isPositive();
                    });
                    var old = owner.owned().getFirst();
                    assertThat(metrics.get("dispatch.city.ownership.held").gauge().value()).isEqualTo(1);
                    application.stop();
                    assertThat(owner.owned()).isEmpty();
                    assertThat(owner.isRunning()).isFalse();
                    application.start();
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                        assertThat(owner.owned()).hasSize(1);
                        assertThat(owner.owned().getFirst().token()).isGreaterThan(old.token());
                    });
                    assertThat(owner.runIfOwned(old, () -> { throw new AssertionError("old token ran work"); })).isFalse();
                });
    }

    private static void awaitGate(CountDownLatch gate) {
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("work gate timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private CityLeaseCoordinator coordinator(Leases store, String holder) {
        return new CityLeaseCoordinator(store, PROPERTIES, new InstanceId(holder), new WorkerProperties(false), new SimpleMeterRegistry());
    }

    private void expire(String city) {
        jdbc.sql("UPDATE platform.leases SET expires_at = clock_timestamp() - interval '1 second' WHERE name = :name")
                .param("name", "city-owner:" + city).update();
    }

    @EnableConfigurationProperties({CityOwnershipProperties.class, WorkerProperties.class})
    static class Properties {
    }
}
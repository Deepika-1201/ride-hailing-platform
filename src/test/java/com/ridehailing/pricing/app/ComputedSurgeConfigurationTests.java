package com.ridehailing.pricing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import com.ridehailing.geography.GeographyApi;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.platform.CityOwnership;
import com.ridehailing.platform.CityOwnership.Claim;
import com.ridehailing.platform.Poller;
import static org.mockito.Mockito.when;

import com.ridehailing.pricing.app.ComputedSurgeCalculator.Step;
import com.ridehailing.pricing.app.SurgeWindows.Surge;
import com.ridehailing.pricing.db.ComputedSurgeRepository;
import com.ridehailing.pricing.db.SurgeRuleRepository.SurgeRule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ComputedSurgeConfigurationTests {

    private static final String PREFIX = "ride.pricing.computed-surge.";
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Properties.class);

    @Test
    void configurationDefaultsToDisabledAndRequiresAnExplicitValidStepTable() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            ComputedSurgeProperties defaults = application.getBean(ComputedSurgeProperties.class);
            assertThat(defaults.enabled()).isFalse();
            assertThat(defaults.cities()).isEmpty();
            assertThat(defaults.maxDrivers()).isEqualTo(5000);
        });
        context.withPropertyValues(PREFIX + "enabled=true").run(application -> assertThat(application).hasFailed());
        configured().run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(ComputedSurgeProperties.class).cities().get("blr"))
                    .containsExactly(new Step(BigDecimal.ZERO, BigDecimal.ONE), new Step(BigDecimal.ONE, new BigDecimal("1.5")));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"max-drivers=0", "max-drivers=10001", "cities.blr[0].ratio-from=1",
        "cities.blr[0].multiplier=1.1", "cities.blr[1].multiplier=2.01", "cities.blr[1].ratio-from=0",
        "cities.blr[1].ratio-from=-1", "cities.blr[1].multiplier=1.001"})
    void invalidSettingsFailBeforeAnySurgeWorkStarts(String setting) {
        configured().withPropertyValues(PREFIX + setting).run(application -> assertThat(application).hasFailed());
    }

    @Test
    void freshComputedValuesReplaceRulesButMissingValuesUseTheNormalRulePath() {
        SurgeRuleCache rules = mock(SurgeRuleCache.class);
        ComputedSurgeRepository repository = mock(ComputedSurgeRepository.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        var properties = new ComputedSurgeProperties(true, Map.of("blr", List.of(new Step(BigDecimal.ZERO, BigDecimal.ONE))), 100);
        SurgeSelection selection = new SurgeSelection(rules, repository, properties, metrics);
        ZonedDateTime local = ZonedDateTime.parse("2026-10-12T12:00:00+05:30[Asia/Kolkata]");
        when(repository.current("blr", "zone")).thenReturn(Optional.of(new BigDecimal("1.20"))).thenReturn(Optional.empty());
        when(rules.of("blr")).thenReturn(List.of(new SurgeRule(UUID.randomUUID(), "blr", "zone", List.of(1),
                LocalTime.of(11, 0), LocalTime.of(13, 0), new BigDecimal("1.50"), true, 0)));

        assertThat(selection.at("blr", "zone", local)).isEqualTo(new Surge(new BigDecimal("1.20"), Surge.Source.COMPUTED));
        verifyNoInteractions(rules);
        assertThat(selection.at("blr", "zone", local)).isEqualTo(new Surge(new BigDecimal("1.50"), Surge.Source.RULE));
        assertThat(selection.at("blr", "elsewhere", local).source()).isEqualTo(Surge.Source.NONE);
        assertThat(metrics.get("pricing.surge.selection").tag("source", "computed").counter().count()).isEqualTo(1);
        assertThat(metrics.get("pricing.surge.selection").tag("source", "rule").counter().count()).isEqualTo(1);
        assertThat(metrics.get("pricing.surge.selection").tag("source", "none").counter().count()).isEqualTo(1);
    }

    @Test
    void disabledOrUnconfiguredCitiesNeverReadComputedValues() {
        SurgeRuleCache rules = mock(SurgeRuleCache.class);
        ComputedSurgeRepository repository = mock(ComputedSurgeRepository.class);
        when(rules.of("blr")).thenReturn(List.of());
        ZonedDateTime now = ZonedDateTime.parse("2026-10-12T12:00:00Z");
        new SurgeSelection(rules, repository, new ComputedSurgeProperties(false, Map.of(), 100), new SimpleMeterRegistry())
                .at("blr", "zone", now);
        new SurgeSelection(rules, repository, new ComputedSurgeProperties(true,
                Map.of("mum", List.of(new Step(BigDecimal.ZERO, BigDecimal.ONE))), 100), new SimpleMeterRegistry())
                .at("blr", "zone", now);
        verifyNoInteractions(repository);
    }

        @Test
        void onlyEnabledDispatchProcessesRegisterThePollerAndOwnershipIsRequired() {
        CityOwnership ownership = mock(CityOwnership.class);
        when(ownership.configuredCities()).thenReturn(Set.of("blr"));
        ApplicationContextRunner runtime = configured().withUserConfiguration(ComputedSurge.class)
            .withBean(ComputedSurgeRepository.class, () -> mock(ComputedSurgeRepository.class))
            .withBean(GeographyApi.class, () -> mock(GeographyApi.class))
            .withBean(LiveIndex.class, () -> mock(LiveIndex.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);
        runtime.withPropertyValues("ride.roles=api,worker,realtime")
            .run(application -> assertThat(application).hasNotFailed().doesNotHaveBean(Poller.class));
        runtime.withPropertyValues("ride.roles=dispatch")
            .run(application -> assertThat(application).hasFailed());
        runtime.withBean(CityOwnership.class, () -> ownership).withPropertyValues("ride.roles=dispatch")
            .run(application -> assertThat(application).hasNotFailed().hasSingleBean(Poller.class));
        when(ownership.configuredCities()).thenReturn(Set.of("mum"));
        runtime.withBean(CityOwnership.class, () -> ownership).withPropertyValues("ride.roles=dispatch")
            .run(application -> assertThat(application).hasFailed());
        runtime.withPropertyValues("ride.roles=dispatch", PREFIX + "enabled=false")
            .run(application -> assertThat(application).hasNotFailed().doesNotHaveBean(Poller.class));
        }

        @Test
        void aYoungOrChangedEpochAndFailedSupplyNeverReachPublication() {
        CityOwnership ownership = mock(CityOwnership.class);
        when(ownership.configuredCities()).thenReturn(Set.of("blr"));
        when(ownership.owned()).thenReturn(List.of(new Claim("blr", "owner", 1)));
        ComputedSurgeRepository repository = mock(ComputedSurgeRepository.class);
        when(repository.due("blr")).thenReturn(true);
        GeographyApi geography = mock(GeographyApi.class);
        when(geography.city("blr")).thenReturn(Optional.of(new GeographyApi.CityView("blr", "City", ZoneId.of("UTC"), "INR", true)));
        LiveIndex index = mock(LiveIndex.class);
        Instant now = Instant.parse("2026-10-12T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneId.of("UTC"));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        var properties = new ComputedSurgeProperties(true, Map.of("blr", List.of(new Step(BigDecimal.ZERO, BigDecimal.ONE))), 100);
        ComputedSurge poller = new ComputedSurge(ownership, repository, properties, geography, index, clock, metrics);
        when(index.epoch("blr")).thenReturn(now.minusSeconds(59));
        assertThat(poller.poll()).isFalse();
        verify(index, never()).available("blr", 100);
        when(index.epoch("blr")).thenReturn(now.minusSeconds(61)).thenReturn(now);
        assertThat(poller.poll()).isFalse();
        when(index.epoch("blr")).thenReturn(now.minusSeconds(61));
        when(index.available("blr", 100)).thenThrow(new IllegalStateException("snapshot limit"));
        assertThat(poller.poll()).isFalse();
        verify(ownership, never()).runIfOwned(any(), any());
        assertThat(metrics.get("pricing.surge.cycles").tag("outcome", "recovering").counter().count()).isEqualTo(2);
        assertThat(metrics.get("pricing.surge.cycles").tag("outcome", "error").counter().count()).isEqualTo(1);
        }

        private ApplicationContextRunner configured() {
        return context.withPropertyValues(PREFIX + "enabled=true", PREFIX + "cities.blr[0].ratio-from=0",
                PREFIX + "cities.blr[0].multiplier=1", PREFIX + "cities.blr[1].ratio-from=1", PREFIX + "cities.blr[1].multiplier=1.5");
    }

    @EnableConfigurationProperties(ComputedSurgeProperties.class)
    static class Properties {
    }
}
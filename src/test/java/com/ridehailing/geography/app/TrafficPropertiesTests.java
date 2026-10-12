package com.ridehailing.geography.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.geography.app.TrafficProperties.Window;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TrafficPropertiesTests {

    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Properties.class);

    @Test
    void windowsIncludeTheStartExcludeTheEndAndSelectTheHighestMatchingFactor() {
        TrafficProperties properties = new TrafficProperties(true, Map.of("blr", List.of(
                window("*", "17:00", "20:00", "1.20"), window("area:AIRPORT", "18:00", "21:00", "1.60"))));
        assertThat(properties.factor("blr", "area:AIRPORT", at("2026-10-12T16:59:59"))).isEqualByComparingTo("1");
        assertThat(properties.factor("blr", "area:AIRPORT", at("2026-10-12T17:00"))).isEqualByComparingTo("1.2");
        assertThat(properties.factor("blr", "area:AIRPORT", at("2026-10-12T18:00"))).isEqualByComparingTo("1.6");
        assertThat(properties.factor("blr", "area:STATION", at("2026-10-12T18:00"))).isEqualByComparingTo("1.2");
        assertThat(properties.factor("blr", "area:AIRPORT", at("2026-10-12T21:00"))).isEqualByComparingTo("1");
        assertThat(properties.factor("mum", "area:AIRPORT", at("2026-10-12T18:00"))).isEqualByComparingTo("1");
        assertThat(properties.factor("blr", "area:AIRPORT", at("2026-10-13T18:00"))).isEqualByComparingTo("1");
        assertThat(properties.factor("blr", null, at("2026-10-12T18:00"))).isEqualByComparingTo("1");
    }

    @Test
    void aMidnightWindowBelongsToTheDayItStarts() {
        TrafficProperties properties = new TrafficProperties(true, Map.of("blr", List.of(window("*", "22:00", "02:00", "1.5"))));
        assertThat(properties.factor("blr", "area:AIRPORT", at("2026-10-12T01:00"))).isEqualByComparingTo("1");
        assertThat(properties.factor("blr", "area:AIRPORT", at("2026-10-12T22:00"))).isEqualByComparingTo("1.5");
        assertThat(properties.factor("blr", "area:AIRPORT", at("2026-10-13T01:59:59"))).isEqualByComparingTo("1.5");
        assertThat(properties.factor("blr", "area:AIRPORT", at("2026-10-13T02:00"))).isEqualByComparingTo("1");
    }

    @Test
    void disabledConfigurationDoesNotAdjustEvenConfiguredWindows() {
        TrafficProperties properties = new TrafficProperties(false, Map.of("blr", List.of(window("*", "17:00", "20:00", "1.5"))));
        assertThat(properties.factor("blr", "area:AIRPORT", at("2026-10-12T18:00"))).isEqualByComparingTo("1");
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(TrafficProperties.class).enabled()).isFalse();
            assertThat(application.getBean(TrafficProperties.class).cities()).isEmpty();
        });
        context.withPropertyValues("ride.routing.traffic.enabled=true").run(application -> assertThat(application).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"multiplier=0.99", "multiplier=3.01", "multiplier=1.001", "multiplier=NaN",
        "days-of-week=0", "days-of-week=8", "days-of-week=", "end-local=17:00", "zone-id=bad zone"})
    void invalidWindowsFailAtStartup(String setting) {
        configured().withPropertyValues("ride.routing.traffic.cities.blr[0]." + setting)
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void validWindowsBindWithoutDependingOnAnExternalTrafficFeed() {
        configured().run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(TrafficProperties.class).factor("blr", "area:AIRPORT", at("2026-10-12T18:00")))
                    .isEqualByComparingTo("1.50");
        });
    }

    private ApplicationContextRunner configured() {
        return context.withPropertyValues("ride.routing.traffic.enabled=true", "ride.routing.traffic.cities.blr[0].zone-id=*",
                "ride.routing.traffic.cities.blr[0].days-of-week=1", "ride.routing.traffic.cities.blr[0].start-local=17:00",
                "ride.routing.traffic.cities.blr[0].end-local=20:00", "ride.routing.traffic.cities.blr[0].multiplier=1.50");
    }

    private static Window window(String zone, String start, String end, String factor) {
        return new Window(zone, List.of(1), LocalTime.parse(start), LocalTime.parse(end), new BigDecimal(factor));
    }

    private static ZonedDateTime at(String local) {
        return ZonedDateTime.parse(local + "+05:30[Asia/Kolkata]");
    }

    @EnableConfigurationProperties(TrafficProperties.class)
    static class Properties {
    }
}
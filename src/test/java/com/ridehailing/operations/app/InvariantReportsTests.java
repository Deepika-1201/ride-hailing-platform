package com.ridehailing.operations.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.operations.app.InvariantReports.InvariantReport;
import com.ridehailing.operations.app.InvariantReports.Violation;
import com.ridehailing.platform.InvariantCheck;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvariantReportsTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID DRIVER = UUID.fromString("0199a3f0-0001-7000-8000-000000000007");
    private static final UUID RIDE = UUID.fromString("0199a3f0-7c2e-7a41-9b3d-5f2e8c1d4a10");

    @Test
    void everyCheckRunsOverEveryCityInItsNumbersOrder() {
        List<String> cities = new java.util.ArrayList<>();
        InvariantCheck tenth = check("I10", city -> {
            cities.add(city);
            return List.of();
        });
        InvariantCheck second = check("I2", city -> {
            cities.add(city);
            return List.of();
        });

        InvariantReport report = new InvariantReports(List.of(tenth, second), CLOCK).check();

        assertThat(report.checks()).containsExactly("I2", "I10");
        assertThat(report.checkedAt()).isEqualTo(CLOCK.instant());
        assertThat(report.violations()).isEmpty();
        assertThat(cities).containsExactly(null, null);
    }

    @Test
    void aViolationNamesTheIdsInItsDetailOnceEachInOrder() {
        String detail = "ride " + RIDE + " is assigned to driver " + DRIVER + ", who is ON_TRIP on ride " + RIDE;
        InvariantCheck check = check("I4", city -> List.of(detail, "no ids here"));

        InvariantReport report = new InvariantReports(List.of(check), CLOCK).check();

        assertThat(report.violations()).containsExactly(new Violation("I4", detail, List.of(RIDE, DRIVER)),
                new Violation("I4", "no ids here", null));
    }

    @Test
    void aCheckThatCantRunIsAViolationAndTheOthersStillRun() {
        InvariantCheck broken = check("I5", city -> {
            throw new IllegalStateException("canceling statement due to statement timeout");
        });
        InvariantCheck working = check("I8", city -> List.of("driver " + DRIVER + " is suspended but AVAILABLE"));

        InvariantReport report = new InvariantReports(List.of(broken, working), CLOCK).check();

        assertThat(report.checks()).containsExactly("I5", "I8");
        assertThat(report.violations()).containsExactly(
                new Violation("I5", "The check couldn't run: IllegalStateException", null),
                new Violation("I8", "driver " + DRIVER + " is suspended but AVAILABLE", List.of(DRIVER)));
    }

    private static InvariantCheck check(String id, java.util.function.Function<String, List<String>> violations) {
        return new InvariantCheck() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<String> violations(String cityId) {
                return violations.apply(cityId);
            }
        };
    }
}

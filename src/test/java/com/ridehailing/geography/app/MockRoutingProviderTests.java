package com.ridehailing.geography.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.geography.RoutingProvider.Route;
import com.ridehailing.shared.GeoPoint;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** ADR-013 as amended, LLD §10.4: haversine × 1.35, at the speed for the local hour of departure. */
class MockRoutingProviderTests {

    private static final GeoPoint INDIRANAGAR = new GeoPoint(12.97194, 77.64115);
    private static final GeoPoint KORAMANGALA = new GeoPoint(12.93524, 77.62448);

    private final MockRoutingProvider routing = new MockRoutingProvider();

    @Test
    void theDistanceIsTheStraightLineWithADetour() {
        Route route = routing.route(INDIRANAGAR, KORAMANGALA, at("2026-10-02T12:00")).orElseThrow();

        assertThat(route.distanceM()).isEqualTo((int) Math.round(INDIRANAGAR.metresTo(KORAMANGALA) * 1.35));
        assertThat(route.distanceM()).as("4.46 km in a straight line").isBetween(6_000, 6_050);
        assertThat(route.source()).isEqualTo(Route.Source.MOCK);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-10-02T05:59, 30", "2026-10-02T06:00, 24", "2026-10-02T07:59, 24", "2026-10-02T08:00, 18",
        "2026-10-02T10:59, 18", "2026-10-02T11:00, 22", "2026-10-02T16:59, 22", "2026-10-02T17:00, 18",
        "2026-10-02T20:59, 18", "2026-10-02T21:00, 26", "2026-10-02T23:59, 26", "2026-10-03T00:00, 30"})
    void theSpeedFollowsTheLocalHourOfDeparture(String localTime, int kmh) {
        Route route = routing.route(INDIRANAGAR, KORAMANGALA, at(localTime)).orElseThrow();

        assertThat(route.durationS()).isEqualTo((int) Math.round(route.distanceM() / (kmh / 3.6)));
    }

    @Test
    void theSameDepartureInstantInAnotherZoneCanHaveAnotherSpeed() {
        ZonedDateTime kolkataMorningPeak = at("2026-10-02T09:00");
        ZonedDateTime sameInstantInUtc = kolkataMorningPeak.withZoneSameInstant(ZoneId.of("UTC"));

        assertThat(routing.route(INDIRANAGAR, KORAMANGALA, kolkataMorningPeak).orElseThrow().durationS())
                .isGreaterThan(routing.route(INDIRANAGAR, KORAMANGALA, sameInstantInUtc).orElseThrow().durationS());
    }

    private static ZonedDateTime at(String localTime) {
        return LocalDateTime.parse(localTime).atZone(ZoneId.of("Asia/Kolkata"));
    }
}

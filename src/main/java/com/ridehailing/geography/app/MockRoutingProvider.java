package com.ridehailing.geography.app;

import com.ridehailing.geography.RoutingProvider;
import com.ridehailing.shared.GeoPoint;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Straight-line distance with a detour factor, at a speed for the local hour of departure (ADR-013, LLD §10.4).
 * Deterministic, so quotes and simulator runs repeat.
 */
@Component
class MockRoutingProvider implements RoutingProvider {

    static final double DETOUR_FACTOR = 1.35;

    /** km/h by local hour: night, morning, morning peak, midday, evening peak, late evening. */
    private static final int[] SPEED_BY_HOUR = {
        30, 30, 30, 30, 30, 30,
        24, 24,
        18, 18, 18,
        22, 22, 22, 22, 22, 22,
        18, 18, 18, 18,
        26, 26, 26};

    @Override
    public Optional<Route> route(GeoPoint from, GeoPoint to, ZonedDateTime departure) {
        int distanceM = (int) Math.round(from.metresTo(to) * DETOUR_FACTOR);
        double metresPerSecond = SPEED_BY_HOUR[departure.getHour()] / 3.6;
        int durationS = (int) Math.round(distanceM / metresPerSecond);
        return Optional.of(new Route(distanceM, durationS, Route.Source.MOCK));
    }
}

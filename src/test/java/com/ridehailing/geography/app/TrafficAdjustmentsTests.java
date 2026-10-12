package com.ridehailing.geography.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ridehailing.geography.GeographyApi;
import com.ridehailing.geography.GeographyApi.CityView;
import com.ridehailing.geography.GeographyApi.Location;
import com.ridehailing.geography.RoutingProvider.DurationMatrix;
import com.ridehailing.geography.RoutingProvider.Route;
import com.ridehailing.geography.app.TrafficProperties.Window;
import com.ridehailing.shared.GeoPoint;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TrafficAdjustmentsTests {

    private static final ZonedDateTime PEAK = ZonedDateTime.parse("2026-10-12T12:30:00Z");
    private static final Location ORIGIN = new Location("blr", "area:AIRPORT");
    private final GeographyApi geography = mock(GeographyApi.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @Test
    void routeUsesTheCityTimeZoneAndLeavesDistanceAndItsRawInputUntouched() {
        TrafficAdjustments traffic = traffic();
        Route raw = new Route(1234, 101, Route.Source.OSRM);
        Route peak = traffic.route(raw, ORIGIN, PEAK);
        assertThat(peak).isEqualTo(new Route(1234, 152, Route.Source.OSRM_ADJUSTED));
        assertThat(traffic.route(raw, ORIGIN, PEAK.minusHours(2))).isSameAs(raw);
        assertThat(raw.durationS()).isEqualTo(101);
        assertThat(traffic.route(peak, ORIGIN, PEAK)).isSameAs(peak);
        assertThat(metrics.get("routing.traffic").tag("kind", "route").tag("outcome", "adjusted").counter().count()).isEqualTo(1);
    }

    @Test
    void mockFallbackAndUnconfiguredCitiesAreNotAdjusted() {
        TrafficAdjustments traffic = traffic();
        for (Route.Source source : List.of(Route.Source.MOCK, Route.Source.MOCK_FALLBACK)) {
            Route route = new Route(1000, 100, source);
            assertThat(traffic.route(route, ORIGIN, PEAK)).isSameAs(route);
        }
        Route raw = new Route(1000, 100, Route.Source.OSRM);
        assertThat(traffic.route(raw, new Location("mum", "area:AIRPORT"), PEAK)).isSameAs(raw);
        verifyNoInteractions(geography);
    }

    @Test
    void matrixAdjustsPerOriginAndPreservesFractionalTimesUnreachableCellsAndRawInput() {
        TrafficAdjustments traffic = traffic();
        List<GeoPoint> origins = List.of(new GeoPoint(12, 77), new GeoPoint(12.1, 77.1), new GeoPoint(12.2, 77.2));
        when(geography.zonesOf("blr", origins)).thenReturn(Map.of(0, "area:AIRPORT", 1, "area:STATION"));
        DurationMatrix raw = new DurationMatrix(List.of(List.of(OptionalDouble.of(10.5), OptionalDouble.empty()),
                List.of(OptionalDouble.of(20.5), OptionalDouble.of(0)), List.of(OptionalDouble.of(30.5), OptionalDouble.of(40.5))));
        DurationMatrix adjusted = traffic.matrix("blr", origins, raw, PEAK);
        assertThat(adjusted.trafficFactors()).containsExactly(1.5, 1.0, 1.0);
        assertThat(adjusted.durationsS().getFirst()).containsExactly(OptionalDouble.of(15.75), OptionalDouble.empty());
        assertThat(adjusted.durationsS().get(1)).isEqualTo(raw.durationsS().get(1));
        assertThat(adjusted.durationsS().get(2)).isEqualTo(raw.durationsS().get(2));
        assertThat(raw.trafficFactors()).isEmpty();
        assertThat(raw.durationsS().getFirst().getFirst()).isEqualTo(OptionalDouble.of(10.5));
        assertThat(traffic.matrix("blr", origins, adjusted, PEAK)).isSameAs(adjusted);
    }

    @Test
    void overflowDoesNotWrapOrReturnAPartiallyAdjustedMatrix() {
        TrafficAdjustments traffic = traffic();
        Route raw = new Route(1000, Integer.MAX_VALUE, Route.Source.OSRM);
        assertThat(traffic.route(raw, ORIGIN, PEAK)).isSameAs(raw);
        List<GeoPoint> origins = List.of(new GeoPoint(12, 77), new GeoPoint(12.1, 77.1));
        when(geography.zonesOf("blr", origins)).thenReturn(Map.of(0, "area:AIRPORT", 1, "area:AIRPORT"));
        DurationMatrix matrix = new DurationMatrix(List.of(List.of(OptionalDouble.of(10)), List.of(OptionalDouble.of(Double.MAX_VALUE))));
        assertThat(traffic.matrix("blr", origins, matrix, PEAK)).isSameAs(matrix);
        assertThat(metrics.get("routing.traffic").tag("kind", "matrix").tag("outcome", "overflow").counter().count()).isEqualTo(1);
    }

    @Test
    void startupRejectsNonOsrmCitiesAndInvalidH3Resolution() {
        TrafficProperties properties = properties("*");
        assertThatThrownBy(() -> new TrafficAdjustments(geography, properties, osrm(false), new H3Zones(), metrics))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OSRM enabled");
        assertThatThrownBy(() -> new TrafficAdjustments(geography, properties("fffffffffffffff"), osrm(true), new H3Zones(), metrics))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("resolution-7");
        assertThatThrownBy(() -> new DurationMatrix(List.of(List.of(OptionalDouble.of(1))), List.of(1.0, 2.0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DurationMatrix(List.of(List.of(OptionalDouble.of(1))), List.of(Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private TrafficAdjustments traffic() {
        when(geography.city("blr")).thenReturn(Optional.of(new CityView("blr", "Bengaluru", ZoneId.of("Asia/Kolkata"), "INR", true)));
        return new TrafficAdjustments(geography, properties("area:AIRPORT"), osrm(true), new H3Zones(), metrics);
    }

    private static TrafficProperties properties(String zone) {
        return new TrafficProperties(true, Map.of("blr", List.of(new Window(zone, List.of(1), LocalTime.of(17, 0),
                LocalTime.of(20, 0), new BigDecimal("1.50")))));
    }

    private static OsrmProperties osrm(boolean enabled) {
        return new OsrmProperties(enabled, URI.create("http://localhost:5000"), Set.of("blr"), Duration.ofMillis(300),
                Duration.ofMinutes(10), Duration.ofMillis(20));
    }
}
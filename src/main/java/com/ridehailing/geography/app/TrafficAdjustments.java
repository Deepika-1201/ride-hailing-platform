package com.ridehailing.geography.app;

import com.ridehailing.geography.GeographyApi;
import com.ridehailing.geography.GeographyApi.Location;
import com.ridehailing.geography.RoutingProvider.DurationMatrix;
import com.ridehailing.geography.RoutingProvider.Route;
import com.ridehailing.shared.GeoPoint;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.springframework.stereotype.Component;

@Component
class TrafficAdjustments {

    private final GeographyApi geography;
    private final TrafficProperties properties;
    private final Map<String, Counter> outcomes = new HashMap<>();

    TrafficAdjustments(GeographyApi geography, TrafficProperties properties, OsrmProperties osrm, H3Zones zones,
            MeterRegistry metrics) {
        if (properties.enabled() && (!osrm.enabled() || !osrm.cities().containsAll(properties.cities().keySet()))) {
            throw new IllegalArgumentException("Traffic-adjusted cities must have OSRM enabled");
        }
        if (properties.enabled()) {
            properties.cities().values().stream().flatMap(List::stream).map(TrafficProperties.Window::zoneId)
                    .filter(zone -> !zone.equals("*") && !zone.startsWith("area:")).forEach(zone -> {
                        if (!zones.isCell(zone)) {
                            throw new IllegalArgumentException("Traffic zones must be H3 resolution-7 cells: " + zone);
                        }
                    });
        }
        this.geography = geography;
        this.properties = properties;
        for (String kind : List.of("route", "matrix")) {
            for (String outcome : List.of("adjusted", "unchanged", "overflow")) {
                outcomes.put(kind + ":" + outcome, metrics.counter("routing.traffic", "kind", kind, "outcome", outcome));
            }
        }
    }

    Route route(Route raw, Location origin, ZonedDateTime departure) {
        if (raw.source() != Route.Source.OSRM || !enabled(origin.cityId())) {
            return raw;
        }
        BigDecimal factor = properties.factor(origin.cityId(), origin.zoneId(), local(origin.cityId(), departure));
        if (factor.compareTo(BigDecimal.ONE) == 0) {
            count("route", "unchanged");
            return raw;
        }
        try {
            int seconds = BigDecimal.valueOf(raw.durationS()).multiply(factor).setScale(0, RoundingMode.HALF_UP).intValueExact();
            count("route", "adjusted");
            return new Route(raw.distanceM(), seconds, Route.Source.OSRM_ADJUSTED);
        } catch (ArithmeticException overflow) {
            count("route", "overflow");
            return raw;
        }
    }

    DurationMatrix matrix(String cityId, List<GeoPoint> origins, DurationMatrix raw, ZonedDateTime departure) {
        if (!enabled(cityId) || !raw.trafficFactors().isEmpty()) {
            return raw;
        }
        if (origins.size() != raw.durationsS().size()) {
            throw new IllegalArgumentException("Traffic matrix origins must match its rows");
        }
        ZonedDateTime local = local(cityId, departure);
        Map<Integer, String> zones = geography.zonesOf(cityId, origins);
        List<Double> factors = new ArrayList<>();
        List<List<OptionalDouble>> adjusted = new ArrayList<>();
        for (int origin = 0; origin < origins.size(); origin++) {
            double factor = properties.factor(cityId, zones.get(origin), local).doubleValue();
            factors.add(factor);
            List<OptionalDouble> row = new ArrayList<>();
            for (OptionalDouble value : raw.durationsS().get(origin)) {
                if (value.isEmpty()) {
                    row.add(value);
                } else {
                    double seconds = value.getAsDouble() * factor;
                    if (!Double.isFinite(seconds)) {
                        count("matrix", "overflow");
                        return raw;
                    }
                    row.add(OptionalDouble.of(seconds));
                }
            }
            adjusted.add(row);
        }
        if (factors.stream().allMatch(factor -> factor == 1)) {
            count("matrix", "unchanged");
            return raw;
        }
        count("matrix", "adjusted");
        return new DurationMatrix(adjusted, factors);
    }

    private boolean enabled(String cityId) {
        return properties.enabled() && properties.cities().containsKey(cityId);
    }

    private ZonedDateTime local(String cityId, ZonedDateTime departure) {
        return departure.withZoneSameInstant(geography.city(cityId).orElseThrow().timeZone());
    }

    private void count(String kind, String outcome) {
        outcomes.get(kind + ":" + outcome).increment();
    }
}
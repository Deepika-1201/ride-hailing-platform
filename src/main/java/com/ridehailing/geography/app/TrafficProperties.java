package com.ridehailing.geography.app;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ride.routing.traffic")
public record TrafficProperties(@DefaultValue("false") boolean enabled, @DefaultValue Map<String, List<Window>> cities) {

    public TrafficProperties {
        cities = cities == null ? Map.of() : cities.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        if ((enabled && cities.isEmpty()) || cities.size() > 100
                || cities.keySet().stream().anyMatch(city -> !city.matches("[a-z]{3,8}"))
                || cities.values().stream().anyMatch(windows -> windows.isEmpty() || windows.size() > 100)) {
            throw new IllegalArgumentException("Traffic adjustment requires 1..100 explicit cities with 1..100 windows each");
        }
    }

    BigDecimal factor(String cityId, String zoneId, ZonedDateTime local) {
        if (!enabled || zoneId == null) {
            return BigDecimal.ONE;
        }
        return cities.getOrDefault(cityId, List.of()).stream()
                .filter(window -> (window.zoneId().equals("*") || window.zoneId().equals(zoneId)) && window.covers(local))
                .map(Window::multiplier).max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
    }

    public record Window(String zoneId, List<Integer> daysOfWeek, LocalTime startLocal, LocalTime endLocal,
            BigDecimal multiplier) {

        public Window {
            if (zoneId == null || !(zoneId.equals("*") || zoneId.matches("[0-9a-f]{15}") || zoneId.matches("area:[A-Z0-9-]{2,40}"))) {
                throw new IllegalArgumentException("Traffic windows require *, an H3 cell, or an area:<code> zone");
            }
            if (daysOfWeek == null || daysOfWeek.isEmpty() || daysOfWeek.stream().anyMatch(day -> day == null || day < 1 || day > 7)
                    || startLocal == null || endLocal == null || startLocal.equals(endLocal)) {
                throw new IllegalArgumentException("Traffic windows require days 1..7 and a nonempty local time window");
            }
            daysOfWeek = daysOfWeek.stream().distinct().sorted().toList();
            if (multiplier == null || multiplier.compareTo(BigDecimal.ONE) < 0 || multiplier.compareTo(new BigDecimal("3.00")) > 0
                    || multiplier.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("Traffic multipliers must be 1.00..3.00 with at most two decimal places");
            }
            multiplier = multiplier.setScale(2);
        }

        boolean covers(ZonedDateTime local) {
            LocalTime time = local.toLocalTime();
            boolean afterStart = !time.isBefore(startLocal);
            boolean beforeEnd = time.isBefore(endLocal);
            if (startLocal.isBefore(endLocal)) {
                return afterStart && beforeEnd && daysOfWeek.contains(local.getDayOfWeek().getValue());
            }
            return (afterStart && daysOfWeek.contains(local.getDayOfWeek().getValue()))
                    || (beforeEnd && daysOfWeek.contains(local.minusDays(1).getDayOfWeek().getValue()));
        }
    }
}
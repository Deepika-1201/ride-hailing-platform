package com.ridehailing.pricing.app;

import com.ridehailing.pricing.app.ComputedSurgeCalculator.Step;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ride.pricing.computed-surge")
public record ComputedSurgeProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue Map<String, List<Step>> cities, @DefaultValue("5000") int maxDrivers) {

    public ComputedSurgeProperties {
        cities = cities == null ? Map.of() : cities.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        if ((enabled && cities.isEmpty()) || cities.size() > 100 || cities.keySet().stream().anyMatch(city -> !city.matches("[a-z]{3,8}"))) {
            throw new IllegalArgumentException("Computed surge requires 1..100 explicit valid cities when enabled");
        }
        cities.values().forEach(ComputedSurgeCalculator::new);
        if (maxDrivers < 1 || maxDrivers > 10_000) {
            throw new IllegalArgumentException("Computed surge max-drivers must be 1..10000");
        }
    }
}
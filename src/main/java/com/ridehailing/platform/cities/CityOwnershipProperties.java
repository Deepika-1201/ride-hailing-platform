package com.ridehailing.platform.cities;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ride.dispatch.city-ownership")
public record CityOwnershipProperties(@DefaultValue("false") boolean enabled, @DefaultValue Set<String> cities,
        @DefaultValue("10s") Duration ttl, @DefaultValue("3s") Duration renewEvery) {

    public CityOwnershipProperties {
        cities = cities == null ? Set.of() : Set.copyOf(cities);
        if ((enabled && cities.isEmpty()) || cities.size() > 100 || cities.stream().anyMatch(city -> !city.matches("[a-z]{3,8}"))) {
            throw new IllegalArgumentException("Enabled city ownership requires 1..100 explicit valid city IDs");
        }
        if (ttl == null || ttl.compareTo(Duration.ofMillis(100)) < 0 || ttl.compareTo(Duration.ofMinutes(5)) > 0
                || renewEvery == null || renewEvery.compareTo(Duration.ofMillis(10)) < 0
                || renewEvery.compareTo(ttl.dividedBy(2)) >= 0) {
            throw new IllegalArgumentException("City ownership TTL must be 100ms..5m; renewal must be at least 10ms and less than half the TTL");
        }
    }
}
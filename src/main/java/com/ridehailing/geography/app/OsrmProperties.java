package com.ridehailing.geography.app;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ride.routing.osrm")
public record OsrmProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://localhost:5000") URI baseUrl,
        @DefaultValue Set<String> cities,
        @DefaultValue("300ms") Duration timeout,
        @DefaultValue("10m") Duration cacheTtl,
        @DefaultValue("20ms") Duration cacheTimeout) {

    public OsrmProperties {
        cities = cities == null ? Set.of() : Set.copyOf(cities);
        if (enabled && cities.isEmpty() || cities.stream().anyMatch(city -> city.isBlank() || city.length() > 32)) {
            throw new IllegalArgumentException("Enabled OSRM routing requires explicit city IDs of at most 32 characters");
        }
        if (baseUrl == null || baseUrl.getHost() == null || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))) {
            throw new IllegalArgumentException("OSRM base URL must be HTTP(S), without credentials, query or fragment");
        }
        if (timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(5)) > 0
                || cacheTtl == null || cacheTtl.toSeconds() < 1 || cacheTtl.compareTo(Duration.ofDays(1)) > 0
                || cacheTimeout == null || cacheTimeout.toMillis() < 1 || cacheTimeout.compareTo(Duration.ofMillis(100)) > 0) {
            throw new IllegalArgumentException("OSRM timeout must be 1ms..5s, cache TTL 1s..1d and cache timeout 1ms..100ms");
        }
    }
}
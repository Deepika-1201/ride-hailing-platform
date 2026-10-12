package com.ridehailing.geography.app;

import com.ridehailing.geography.RoutingProvider;
import com.ridehailing.platform.Valkey;
import com.ridehailing.shared.GeoPoint;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Optional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

class CachedRoutingProvider implements RoutingProvider {

    private final RoutingProvider delegate;
    private final Valkey valkey;
    private final JsonMapper json;
    private final ObjectReader reader;
    private final H3Zones zones;
    private final String prefix;
    private final Duration ttl;
    private final Duration timeout;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    CachedRoutingProvider(RoutingProvider delegate, Valkey valkey, JsonMapper json, H3Zones zones, URI baseUrl,
            Duration ttl, Duration timeout, MeterRegistry metrics) {
        if (ttl == null || ttl.toSeconds() < 1 || ttl.compareTo(Duration.ofDays(1)) > 0
                || timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofMillis(100)) > 0) {
            throw new IllegalArgumentException("Routing cache TTL must be 1s..1d and each cache wait 1ms..100ms");
        }
        this.delegate = delegate;
        this.valkey = valkey;
        this.json = json;
        reader = json.readerFor(Route.class)
            .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        this.zones = zones;
        this.ttl = ttl;
        this.timeout = timeout;
        prefix = "routing:osrm:v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(baseUrl.toString().getBytes(StandardCharsets.UTF_8));
        hits = metrics.counter("routing.cache", "outcome", "hit");
        misses = metrics.counter("routing.cache", "outcome", "miss");
        errors = metrics.counter("routing.cache", "outcome", "error");
    }

    @Override
    public Optional<Route> route(GeoPoint from, GeoPoint to, ZonedDateTime departure) {
        if (valkey == null) {
            return delegate.route(from, to, departure);
        }
        String key = prefix + ":{" + zones.routingCellOf(from) + "}:" + zones.routingCellOf(to);
        try {
            String value = valkey.await("routing-cache-read", timeout, valkey.commands().get(key));
            if (value != null) {
                Route cached = reader.readValue(value);
                if (cached.source() != Route.Source.OSRM || cached.distanceM() < 0 || cached.durationS() < 0) {
                    throw new IllegalArgumentException("Invalid cached OSRM route");
                }
                hits.increment();
                return Optional.of(cached);
            }
            misses.increment();
        } catch (RuntimeException unavailable) {
            errors.increment();
        }
        Optional<Route> result = delegate.route(from, to, departure);
        result.filter(route -> route.source() == Route.Source.OSRM).ifPresent(route -> {
            try {
                valkey.await("routing-cache-write", timeout, valkey.commands().setex(key, ttl.toSeconds(), json.writeValueAsString(route)));
            } catch (RuntimeException unavailable) {
                errors.increment();
            }
        });
        return result;
    }
}
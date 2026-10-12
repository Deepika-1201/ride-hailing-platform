package com.ridehailing.geography.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ridehailing.geography.RoutingProvider;
import com.ridehailing.geography.RoutingProvider.Route;
import com.ridehailing.platform.Valkey;
import com.ridehailing.shared.GeoPoint;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class CachedRoutingProviderTests {

    private static final GeoPoint FROM = new GeoPoint(12.97194, 77.64115);
    private static final GeoPoint TO = new GeoPoint(12.93524, 77.62448);
    private static final ZonedDateTime DEPARTURE = ZonedDateTime.parse("2026-10-12T18:00:00+05:30[Asia/Kolkata]");
    private static final Route ROAD = new Route(6500, 700, Route.Source.OSRM);
    private static final Duration TIMEOUT = Duration.ofMillis(20);

    private final JsonMapper json = JsonMapper.builder().build();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final RoutingProvider delegate = mock(RoutingProvider.class);
    private final Valkey valkey = mock(Valkey.class);
    private final H3Zones zones = new H3Zones();

    @SuppressWarnings("unchecked")
    private final RedisClusterAsyncCommands<String, String> commands = mock(RedisClusterAsyncCommands.class);

    @SuppressWarnings("unchecked")
    private final RedisFuture<String> read = mock(RedisFuture.class);

    @SuppressWarnings("unchecked")
    private final RedisFuture<String> write = mock(RedisFuture.class);

    CachedRoutingProviderTests() {
        when(valkey.commands()).thenReturn(commands);
        when(commands.get(anyString())).thenReturn(read);
        when(commands.setex(anyString(), anyLong(), anyString())).thenReturn(write);
        when(delegate.route(any(), any(), any())).thenReturn(Optional.of(ROAD));
    }

    @Test
    void aMissStoresOnlyTheRoadResultWithATenMinuteTtl() {
        assertThat(routing("http://localhost:5000").route(FROM, TO, DEPARTURE)).contains(ROAD);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(commands).setex(key.capture(), eq(600L), eq(json.writeValueAsString(ROAD)));
        assertThat(key.getValue()).contains("routing:osrm:v1:", ":{" + zones.routingCellOf(FROM) + "}:" + zones.routingCellOf(TO));
        verify(valkey).await("routing-cache-read", TIMEOUT, read);
        verify(valkey).await("routing-cache-write", TIMEOUT, write);
        assertThat(metrics.get("routing.cache").tag("outcome", "miss").counter().count()).isEqualTo(1);
    }

    @Test
    void aHitSkipsOsrmAndNearbyPointsShareTheSameResolutionNineKey() {
        when(valkey.await("routing-cache-read", TIMEOUT, read)).thenReturn(json.writeValueAsString(ROAD));
        CachedRoutingProvider routing = routing("http://localhost:5000");
        GeoPoint nearby = new GeoPoint(FROM.lat() + 0.0000001, FROM.lon());

        assertThat(routing.route(FROM, TO, DEPARTURE)).contains(ROAD);
        assertThat(routing.route(nearby, TO, DEPARTURE.plusHours(1))).contains(ROAD);

        verify(delegate, never()).route(any(), any(), any());
        verify(commands, org.mockito.Mockito.times(2)).get(org.mockito.ArgumentMatchers.endsWith(
                ":{" + zones.routingCellOf(FROM) + "}:" + zones.routingCellOf(TO)));
        assertThat(metrics.get("routing.cache").tag("outcome", "hit").counter().count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "{}", "{\"distanceM\":1,\"durationS\":1,\"source\":\"MOCK_FALLBACK\"}",
        "{\"distanceM\":1,\"durationS\":1,\"source\":\"OSRM_ADJUSTED\"}",
        "{\"distanceM\":-1,\"durationS\":1,\"source\":\"OSRM\"}", "{\"source\":\"OSRM\"}",
        "{\"distanceM\":null,\"durationS\":1,\"source\":\"OSRM\"}",
        "{\"distanceM\":1.5,\"durationS\":1,\"source\":\"OSRM\"}",
        "{\"distanceM\":1,\"durationS\":1,\"source\":\"OSRM\"} {}"})
    void corruptOrNonRoadCacheEntriesAreIgnored(String cached) {
        when(valkey.await("routing-cache-read", TIMEOUT, read)).thenReturn(cached);

        assertThat(routing("http://localhost:5000").route(FROM, TO, DEPARTURE)).contains(ROAD);
        verify(delegate).route(FROM, TO, DEPARTURE);
        assertThat(metrics.get("routing.cache").tag("outcome", "error").counter().count()).isEqualTo(1);
    }

    @Test
    void readAndWriteFailuresDoNotHideTheRoute() {
        when(valkey.await("routing-cache-read", TIMEOUT, read)).thenThrow(new RedisException("cache unavailable"));
        when(valkey.await("routing-cache-write", TIMEOUT, write)).thenThrow(new RedisException("cache unavailable"));

        assertThat(routing("http://localhost:5000").route(FROM, TO, DEPARTURE)).contains(ROAD);
        assertThat(metrics.get("routing.cache").tag("outcome", "error").counter().count()).isEqualTo(2);
    }

    @Test
    void fallbackAndNoRouteResponsesAreNeverCached() {
        CachedRoutingProvider routing = routing("http://localhost:5000");
        Route fallback = new Route(5000, 1000, Route.Source.MOCK_FALLBACK);
        when(delegate.route(any(), any(), any())).thenReturn(Optional.of(fallback)).thenReturn(Optional.empty());

        assertThat(routing.route(FROM, TO, DEPARTURE)).contains(fallback);
        assertThat(routing.route(FROM, TO, DEPARTURE)).isEmpty();
        verify(commands, never()).setex(anyString(), anyLong(), anyString());
    }

    @Test
    void changingTheServerOrDirectionCannotReuseAnotherRoute() {
        routing("http://first:5000").route(FROM, TO, DEPARTURE);
        routing("http://second:5000").route(FROM, TO, DEPARTURE);
        routing("http://first:5000").route(TO, FROM, DEPARTURE);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(commands, org.mockito.Mockito.times(3)).get(keys.capture());
        assertThat(keys.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void memoryModeCanUseOsrmWithoutACache() {
        CachedRoutingProvider routing = new CachedRoutingProvider(delegate, null, json, zones, URI.create("http://localhost:5000"),
                Duration.ofMinutes(10), TIMEOUT, metrics);

        assertThat(routing.route(FROM, TO, DEPARTURE)).contains(ROAD);
        verify(valkey, never()).commands();
    }

    private CachedRoutingProvider routing(String baseUrl) {
        return new CachedRoutingProvider(delegate, valkey, json, zones, URI.create(baseUrl), Duration.ofMinutes(10), TIMEOUT, metrics);
    }
}
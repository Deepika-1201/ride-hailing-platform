package com.ridehailing.geography.app;

import com.ridehailing.geography.RoutingProvider;
import com.ridehailing.shared.GeoPoint;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OsrmRoutingProvider implements RoutingProvider {

    static final int MAX_RESPONSE_BYTES = 65_536;

    private final URI baseUrl;
    private final Duration timeout;
    private final HttpClient http;
    private final JsonMapper json;
    private final MockRoutingProvider fallback;
    private final Counter successful;
    private final Counter unavailable;
    private final Counter noRoute;

    OsrmRoutingProvider(URI baseUrl, Duration timeout, HttpClient http, JsonMapper json, MockRoutingProvider fallback,
            MeterRegistry metrics) {
        if (baseUrl == null || baseUrl.getHost() == null || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                || timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("OSRM needs an HTTP base URL and a timeout between 1ms and 5s");
        }
        this.baseUrl = URI.create(baseUrl.toString().replaceAll("/+$", "") + "/");
        this.timeout = timeout;
        this.http = http;
        this.json = json;
        this.fallback = fallback;
        successful = metrics.counter("routing.requests", "provider", "osrm", "outcome", "success");
        unavailable = metrics.counter("routing.requests", "provider", "osrm", "outcome", "fallback");
        noRoute = metrics.counter("routing.requests", "provider", "osrm", "outcome", "no_route");
    }

    @Override
    public Optional<Route> route(GeoPoint from, GeoPoint to, ZonedDateTime departure) {
        URI uri = baseUrl.resolve("route/v1/driving/" + coordinates(from) + ";" + coordinates(to) + "?overview=false&steps=false");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET().build();
        CompletableFuture<HttpResponse<byte[]>> response;
        try {
            response = http.sendAsync(request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES));
        } catch (RuntimeException unavailable) {
            return fallback(from, to, departure);
        }
        try {
            HttpResponse<byte[]> received = response.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (received.statusCode() != 200 && received.statusCode() != 400) {
                return fallback(from, to, departure);
            }
            JsonNode body = json.readTree(received.body());
            if ("NoRoute".equals(body.path("code").asString())) {
                noRoute.increment();
                return Optional.empty();
            }
            JsonNode routes = body.path("routes");
            if (received.statusCode() != 200 || !"Ok".equals(body.path("code").asString()) || !routes.isArray() || routes.isEmpty()) {
                return fallback(from, to, departure);
            }
            Route route = new Route(measurement(routes.get(0).path("distance")), measurement(routes.get(0).path("duration")), Route.Source.OSRM);
            successful.increment();
            return Optional.of(route);
        } catch (InterruptedException interrupted) {
            response.cancel(true);
            Thread.currentThread().interrupt();
            return fallback(from, to, departure);
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            response.cancel(true);
            return fallback(from, to, departure);
        }
    }

    private Optional<Route> fallback(GeoPoint from, GeoPoint to, ZonedDateTime departure) {
        unavailable.increment();
        return fallback.route(from, to, departure).map(route -> new Route(route.distanceM(), route.durationS(), Route.Source.MOCK_FALLBACK));
    }

    private static int measurement(JsonNode value) {
        double number = value.asDouble(Double.NaN);
        if (!value.isNumber() || !Double.isFinite(number) || number < 0 || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("OSRM distance and duration must be finite nonnegative integer-range measurements");
        }
        return (int) Math.round(number);
    }

    private static String coordinates(GeoPoint point) {
        return point.lon() + "," + point.lat();
    }
}
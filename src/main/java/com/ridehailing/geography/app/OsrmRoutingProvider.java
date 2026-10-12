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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OsrmRoutingProvider implements RoutingProvider {

    static final int MAX_RESPONSE_BYTES = 65_536;
    static final int MAX_TABLE_COORDINATES = 100;

    private final URI baseUrl;
    private final Duration timeout;
    private final HttpClient http;
    private final JsonMapper json;
    private final MockRoutingProvider fallback;
    private final Counter successful;
    private final Counter unavailable;
    private final Counter noRoute;
    private final Counter matrixSuccessful;
    private final Counter matrixUnavailable;

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
        matrixSuccessful = metrics.counter("routing.matrix.requests", "provider", "osrm", "outcome", "success");
        matrixUnavailable = metrics.counter("routing.matrix.requests", "provider", "osrm", "outcome", "fallback");
    }

    @Override
    public Optional<Route> route(GeoPoint from, GeoPoint to, ZonedDateTime departure) {
        try {
            HttpResponse<byte[]> received = request("route/v1/driving/" + coordinates(from) + ";" + coordinates(to)
                    + "?overview=false&steps=false");
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
            Thread.currentThread().interrupt();
            return fallback(from, to, departure);
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            return fallback(from, to, departure);
        }
    }

    @Override
    public Optional<DurationMatrix> matrix(String cityId, List<GeoPoint> origins, List<GeoPoint> destinations, ZonedDateTime departure) {
        origins = List.copyOf(origins);
        destinations = List.copyOf(destinations);
        if (origins.isEmpty() || destinations.isEmpty() || origins.size() + destinations.size() > MAX_TABLE_COORDINATES) {
            throw new IllegalArgumentException("OSRM tables require origins and destinations totalling at most 100 coordinates");
        }
        String points = Stream.concat(origins.stream(), destinations.stream()).map(OsrmRoutingProvider::coordinates)
                .collect(Collectors.joining(";"));
        String sources = indexes(0, origins.size());
        String targets = indexes(origins.size(), origins.size() + destinations.size());
        try {
            HttpResponse<byte[]> received = request("table/v1/driving/" + points + "?sources=" + sources
                    + "&destinations=" + targets + "&annotations=duration");
            JsonNode body = json.readTree(received.body());
            JsonNode durations = body.path("durations");
            if (received.statusCode() != 200 || !"Ok".equals(body.path("code").asString())
                    || !durations.isArray() || durations.size() != origins.size()) {
                return unavailableMatrix();
            }
            List<List<OptionalDouble>> result = new ArrayList<>();
            for (JsonNode row : durations) {
                if (!row.isArray() || row.size() != destinations.size()) {
                    return unavailableMatrix();
                }
                List<OptionalDouble> values = new ArrayList<>();
                for (JsonNode value : row) {
                    values.add(value.isNull() ? OptionalDouble.empty() : OptionalDouble.of(measurementValue(value)));
                }
                result.add(values);
            }
            DurationMatrix matrix = new DurationMatrix(result);
            matrixSuccessful.increment();
            return Optional.of(matrix);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unavailableMatrix();
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            return unavailableMatrix();
        }
    }

    private HttpResponse<byte[]> request(String path) throws InterruptedException, ExecutionException, TimeoutException {
        HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve(path)).timeout(timeout).header("Accept", "application/json").GET().build();
        var response = http.sendAsync(request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES));
        try {
            return response.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } finally {
            if (!response.isDone()) {
                response.cancel(true);
            }
        }
    }

    private Optional<DurationMatrix> unavailableMatrix() {
        matrixUnavailable.increment();
        return Optional.empty();
    }

    private static String indexes(int from, int to) {
        return IntStream.range(from, to).mapToObj(Integer::toString).collect(Collectors.joining(";"));
    }

    private Optional<Route> fallback(GeoPoint from, GeoPoint to, ZonedDateTime departure) {
        unavailable.increment();
        return fallback.route(from, to, departure).map(route -> new Route(route.distanceM(), route.durationS(), Route.Source.MOCK_FALLBACK));
    }

    private static int measurement(JsonNode value) {
        return (int) Math.round(measurementValue(value));
    }

    private static double measurementValue(JsonNode value) {
        double number = value.asDouble(Double.NaN);
        if (!value.isNumber() || !Double.isFinite(number) || number < 0 || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("OSRM distance and duration must be finite nonnegative integer-range measurements");
        }
        return number;
    }

    private static String coordinates(GeoPoint point) {
        return point.lon() + "," + point.lat();
    }
}
package com.ridehailing.geography.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ridehailing.geography.GeographyApi;
import com.ridehailing.geography.RoutingProvider;
import com.ridehailing.geography.RoutingProvider.Route;
import com.ridehailing.shared.GeoPoint;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class OsrmRoutingProviderTests {

    private static final GeoPoint FROM = new GeoPoint(12.97194, 77.64115);
    private static final GeoPoint TO = new GeoPoint(12.93524, 77.62448);
    private static final ZonedDateTime DEPARTURE = ZonedDateTime.parse("2026-10-12T18:00:00+05:30[Asia/Kolkata]");

    private final JsonMapper json = JsonMapper.builder().build();
    private final MockRoutingProvider mock = new MockRoutingProvider();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final AtomicReference<Handler> handler = new AtomicReference<>(exchange -> reply(exchange, 200,
            "{\"code\":\"Ok\",\"routes\":[{\"distance\":6656.6,\"duration\":688.3}]}"));
    private HttpServer server;
    private ExecutorService executor;
    private HttpClient http;
    private URI baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                handler.get().handle(exchange);
            } catch (IOException ignored) {
            }
        });
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/routing/");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.close();
        http.close();
        metrics.close();
    }

    @Test
    void sendsLongitudeFirstAndReturnsTheRoadMeasurementsWithTheirSource() {
        AtomicReference<String> requested = new AtomicReference<>();
        handler.set(exchange -> {
            requested.set(exchange.getRequestURI().toString());
            reply(exchange, 200, "{\"code\":\"Ok\",\"routes\":[{\"distance\":6656.6,\"duration\":688.3}],\"waypoints\":[]}");
        });

        Route route = routing().route(FROM, TO, DEPARTURE).orElseThrow();

        assertThat(requested.get()).isEqualTo("/routing/route/v1/driving/77.64115,12.97194;77.62448,12.93524?overview=false&steps=false");
        assertThat(route).isEqualTo(new Route(6657, 688, Route.Source.OSRM));
        assertThat(count("success")).isEqualTo(1);
        assertThat(count("fallback")).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 400})
    void aGenuineMissingRoadRouteDoesNotBecomeAMockRoute(int status) {
        handler.set(exchange -> reply(exchange, status, "{\"code\":\"NoRoute\",\"message\":\"No route found\"}"));

        assertThat(routing().route(FROM, TO, DEPARTURE)).isEmpty();
        assertThat(count("no_route")).isEqualTo(1);
        assertThat(count("fallback")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "{}", "{\"code\":\"InvalidQuery\"}", "{\"code\":\"Ok\",\"routes\":[]}",
        "{\"code\":\"Ok\",\"routes\":[{}]}", "{\"code\":\"Ok\",\"routes\":[{\"distance\":-1,\"duration\":1}]}",
        "{\"code\":\"Ok\",\"routes\":[{\"distance\":1,\"duration\":2147483648}]}",
        "{\"code\":\"Ok\",\"routes\":[{\"distance\":\"5\",\"duration\":1}]}"})
    void malformedResponsesUseAnExplicitFallback(String body) {
        handler.set(exchange -> reply(exchange, 200, body));

        assertFallback(routing().route(FROM, TO, DEPARTURE).orElseThrow());
        assertThat(count("fallback")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 429, 500, 503})
    void serviceErrorsAndRedirectsUseTheFallback(int status) {
        handler.set(exchange -> reply(exchange, status, "{\"code\":\"Error\"}"));

        assertFallback(routing().route(FROM, TO, DEPARTURE).orElseThrow());
    }

    @Test
    void theDeadlineCoversABodyThatStallsAfterHeaders() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        handler.set(exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        long started = System.nanoTime();
        try {
            assertFallback(routing().route(FROM, TO, DEPARTURE).orElseThrow());
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        } finally {
            release.countDown();
        }
    }

    @Test
    void connectionFailureFallsBackAndCallerInterruptionIsPreserved() {
        server.stop(0);
        assertFallback(routing().route(FROM, TO, DEPARTURE).orElseThrow());
        Thread.currentThread().interrupt();
        try {
            assertFallback(routing().route(FROM, TO, DEPARTURE).orElseThrow());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void oversizedResponsesAreDroppedAndAClosedClientFallsBack() {
        handler.set(exchange -> reply(exchange, 200, "x".repeat(OsrmRoutingProvider.MAX_RESPONSE_BYTES + 1)));
        assertFallback(routing().route(FROM, TO, DEPARTURE).orElseThrow());
        http.close();
        assertFallback(routing().route(FROM, TO, DEPARTURE).orElseThrow());
    }

    @Test
    void invalidBaseUrlsAndTimeoutsAreRejected() {
        for (String url : new String[] {"file:///tmp/routes", "http://user:password@localhost", "http://localhost?q=x", "http://localhost#route"}) {
            assertThatThrownBy(() -> new OsrmRoutingProvider(URI.create(url), Duration.ofMillis(300), http, json, mock, metrics))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new OsrmRoutingProvider(baseUrl, Duration.ZERO, http, json, mock, metrics))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneTableCallPreservesDirectionFractionalSecondsAndUnreachableCells() {
        AtomicReference<String> requested = new AtomicReference<>();
        handler.set(exchange -> {
            requested.set(exchange.getRequestURI().toString());
            reply(exchange, 200, "{\"code\":\"Ok\",\"durations\":[[14.25,0],[null,7.5]]}");
        });

        var matrix = routing().matrix("blr", List.of(FROM, TO), List.of(TO, FROM), DEPARTURE).orElseThrow();

        assertThat(requested.get()).isEqualTo("/routing/table/v1/driving/77.64115,12.97194;77.62448,12.93524;"
                + "77.62448,12.93524;77.64115,12.97194?sources=0;1&destinations=2;3&annotations=duration");
        assertThat(matrix.durationsS()).containsExactly(List.of(OptionalDouble.of(14.25), OptionalDouble.of(0)),
                List.of(OptionalDouble.empty(), OptionalDouble.of(7.5)));
        assertThat(metrics.get("routing.matrix.requests").tag("outcome", "success").counter().count()).isEqualTo(1);
        assertThatThrownBy(() -> matrix.durationsS().getFirst().add(OptionalDouble.empty())).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "{}", "{\"code\":\"NoTable\"}", "{\"code\":\"Ok\",\"durations\":[]}",
        "{\"code\":\"Ok\",\"durations\":[[1,2]]}", "{\"code\":\"Ok\",\"durations\":[[-1]]}",
        "{\"code\":\"Ok\",\"durations\":[[\"7\"]]}", "{\"code\":\"Ok\",\"durations\":[[2147483648]]}"})
    void invalidTablesReturnUnavailableRatherThanFabricatingEtas(String body) {
        handler.set(exchange -> reply(exchange, 200, body));

        assertThat(routing().matrix("blr", List.of(FROM), List.of(TO), DEPARTURE)).isEmpty();
        assertThat(metrics.get("routing.matrix.requests").tag("outcome", "fallback").counter().count()).isEqualTo(1);
    }

    @Test
    void tableFailuresAreBoundedAndUnavailableTablesDoNotBecomeMockMatrices() {
        handler.set(exchange -> reply(exchange, 503, "{\"code\":\"Ok\",\"durations\":[[1]]}"));
        assertThat(routing().matrix("blr", List.of(FROM), List.of(TO), DEPARTURE)).isEmpty();
        server.stop(0);
        assertThat(routing().matrix("blr", List.of(FROM), List.of(TO), DEPARTURE)).isEmpty();
        assertThat(mock.matrix("blr", List.of(FROM), List.of(TO), DEPARTURE)).isEmpty();
    }

    @Test
    void tableCoordinateLimitsAreCheckedBeforeSendingARequest() {
        assertThatThrownBy(() -> routing().matrix("blr", List.of(), List.of(TO), DEPARTURE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> routing().matrix("blr", java.util.Collections.nCopies(100, FROM), List.of(TO), DEPARTURE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultStartupKeepsExactlyOneMockProviderWithoutAnHttpClient() {
        context(mock(GeographyApi.class)).run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(RoutingProvider.class).doesNotHaveBean(HttpClient.class);
            assertThat(application.getBean(RoutingProvider.class)).isInstanceOf(MockRoutingProvider.class);
            assertThat(application.getBean(OsrmProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void explicitCitiesSelectOsrmWhileOtherCitiesKeepTheMock() {
        GeographyApi geography = mock(GeographyApi.class);
        when(geography.locate(FROM)).thenReturn(Optional.of(new GeographyApi.Location("blr", "zone")));
        when(geography.locate(TO)).thenReturn(Optional.of(new GeographyApi.Location("other", "zone")));
        context(geography).withPropertyValues("ride.routing.osrm.enabled=true", "ride.routing.osrm.cities=blr")
                .run(application -> {
                    assertThat(application).hasNotFailed().hasSingleBean(RoutingProvider.class).hasSingleBean(HttpClient.class);
                    RoutingProvider selected = application.getBean(RoutingProvider.class);
                    assertThat(selected.route(FROM, TO, DEPARTURE)).contains(new Route(6657, 688, Route.Source.OSRM));
                    assertThat(selected.route(TO, FROM, DEPARTURE)).isEqualTo(mock.route(TO, FROM, DEPARTURE));
                    handler.set(exchange -> reply(exchange, 200, "{\"code\":\"Ok\",\"durations\":[[12.75]]}"));
                    assertThat(selected.matrix("blr", List.of(FROM), List.of(TO), DEPARTURE)).hasValueSatisfying(matrix ->
                            assertThat(matrix.durationsS().getFirst().getFirst()).isEqualTo(OptionalDouble.of(12.75)));
                    assertThat(selected.matrix("other", List.of(FROM), List.of(TO), DEPARTURE)).isEmpty();
                    handler.set(exchange -> reply(exchange, 400, "{\"code\":\"NoRoute\"}"));
                    assertThat(selected.route(FROM, TO, DEPARTURE)).isEmpty();
                });
    }

    @Test
    void aStalledTableBodyCannotHoldDispatchPastItsTimeout() {
        CountDownLatch release = new CountDownLatch(1);
        handler.set(exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        long started = System.nanoTime();
        try {
            assertThat(routing().matrix("blr", List.of(FROM), List.of(TO), DEPARTURE)).isEmpty();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        } finally {
            release.countDown();
        }
    }

    @Test
    void matrixValuesCannotContainNegativeNonfiniteOrRaggedRows() {
        for (double invalid : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> new RoutingProvider.DurationMatrix(List.of(List.of(OptionalDouble.of(invalid)))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new RoutingProvider.DurationMatrix(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RoutingProvider.DurationMatrix(List.of(List.of(OptionalDouble.of(1)), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cities=", "timeout=0ms", "timeout=6s", "cache-ttl=0s", "cache-ttl=2d",
        "cache-timeout=0ms", "cache-timeout=101ms", "base-url=file:///tmp/osrm", "base-url=http://user:pass@localhost"})
    void unsafeRoutingConfigurationFailsStartup(String setting) {
        context(mock(GeographyApi.class)).withPropertyValues("ride.routing.osrm.enabled=true", "ride.routing.osrm.cities=blr")
                .withPropertyValues("ride.routing.osrm." + setting)
                .run(application -> assertThat(application).hasFailed());
    }

    private ApplicationContextRunner context(GeographyApi geography) {
        return new ApplicationContextRunner()
                .withUserConfiguration(Properties.class, MockRoutingProvider.class, OsrmConfiguration.class, H3Zones.class)
                .withBean(JsonMapper.class, () -> json).withBean(MeterRegistry.class, () -> metrics)
                .withBean(GeographyApi.class, () -> geography)
                .withPropertyValues("ride.routing.osrm.base-url=" + baseUrl);
    }

    private OsrmRoutingProvider routing() {
        return new OsrmRoutingProvider(baseUrl, Duration.ofMillis(300), http, json, mock, metrics);
    }

    private void assertFallback(Route actual) {
        Route expected = mock.route(FROM, TO, DEPARTURE).orElseThrow();
        assertThat(actual).isEqualTo(new Route(expected.distanceM(), expected.durationS(), Route.Source.MOCK_FALLBACK));
    }

    private double count(String outcome) {
        return metrics.get("routing.requests").tag("outcome", outcome).counter().count();
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OsrmProperties.class)
    static class Properties {
    }
}
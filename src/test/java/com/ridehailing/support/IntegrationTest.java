package com.ridehailing.support;

import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.PublicEndpoint;
import com.ridehailing.platform.timers.TimerFiring;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole application on random ports against the shared PostGIS container. Subclasses choose the roles. Background
 * loops are stopped, so tests drive them step by step, and consumer retries are quick.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "management.server.port=0",
    "ride.workers.autostart=false",
    "ride.outbox.retry-delays=10ms,10ms,10ms"
})
@Import({IntegrationTest.RoleProbeController.class, IdempotencyProbeController.class, TestHandlers.class,
    AccessProbes.ByMethod.class, AccessProbes.DriverByClass.class, TestUsers.class, TestCities.class, TestPrices.class,
    TestDrivers.class, TestRides.class, TimerFiring.class})
@ActiveProfiles("test")
public abstract class IntegrationTest {

    protected static final String API_PROBE = "/test/role-probe";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> Postgis.shared().getJdbcUrl());
        registry.add("spring.datasource.username", () -> Postgis.shared().getUsername());
        registry.add("spring.datasource.password", () -> Postgis.shared().getPassword());
    }

    @LocalServerPort
    protected int port;

    @LocalManagementPort
    protected int managementPort;

    @Autowired
    private ApplicationContext context;

    /** The role-bound background loops this process has: the relay (worker) and the timer poller (dispatch). */
    protected List<String> backgroundLoops() {
        return Stream.of("outboxRelay", "timerPoller").filter(context::containsBean).toList();
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    protected HttpResponse<String> get(int targetPort, String path) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path)).GET());
    }

    /** A GET on the API port with an {@code Authorization} header value, such as {@code Bearer ey…}. */
    protected HttpResponse<String> getAs(String authorization, String path) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", authorization)
                .GET());
    }

    protected HttpResponse<String> postJson(String path, Map<String, String> headers, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return send(request);
    }

    /** Any method on the API port; {@code authorization} and {@code body} (JSON) may be null. */
    protected HttpResponse<String> call(String method, String authorization, String path, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return send(request);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        HttpRequest built = request.timeout(Duration.ofSeconds(10)).build();
        try {
            return http.send(built, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AssertionError(built.method() + " " + built.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    protected static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    protected List<String> reportedRoles() {
        return json(get(managementPort, "/actuator/info")).get("roles").valueStream().map(JsonNode::asString).toList();
    }

    /** Stands in for a module's public controller until the modules have their own. */
    @TestComponent
    @ApiController
    @PublicEndpoint
    static class RoleProbeController {

        @GetMapping(API_PROBE)
        Map<String, Object> probe() {
            return Map.of("served", true);
        }
    }
}

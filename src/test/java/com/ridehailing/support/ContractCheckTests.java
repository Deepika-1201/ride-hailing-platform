package com.ridehailing.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The contract checks must fail on what they exist to catch, or the contract tests prove nothing. */
class ContractCheckTests {

    private static final String CITY = """
            {"id": "blr", "name": "Bengaluru", "time_zone": "Asia/Kolkata", "currency": "INR", "active": true,
             "version": 0, "bounds": {"type": "Polygon", "coordinates": [[[77, 12], [78, 12], [78, 13], [77, 12]]]}}
            """;
    private static final Map<String, List<String>> JSON_HEADERS = Map.of(
            "Content-Type", List.of("application/json"), "X-Request-Id", List.of("req_1"));

    @Test
    void aDocumentedResponseConforms() {
        assertThatCode(() -> OpenApiContract.assertConforms("GET", "/v1/admin/cities/{city_id}",
                response(200, JSON_HEADERS, CITY))).doesNotThrowAnyException();
    }

    @Test
    void anUndocumentedStatusFails() {
        assertThatThrownBy(() -> OpenApiContract.assertConforms("GET", "/v1/admin/cities/{city_id}",
                response(418, JSON_HEADERS, CITY))).isInstanceOf(AssertionError.class)
                .hasMessageContaining("status is documented");
    }

    @Test
    void aMissingRequiredHeaderFails() {
        assertThatThrownBy(() -> OpenApiContract.assertConforms("GET", "/v1/admin/cities/{city_id}",
                response(200, Map.of("Content-Type", List.of("application/json")), CITY)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("X-Request-Id");
    }

    @Test
    void aMissingRequiredPropertyFails() {
        assertThatThrownBy(() -> OpenApiContract.assertConforms("GET", "/v1/admin/cities/{city_id}",
                response(200, JSON_HEADERS, CITY.replace("\"version\": 0,", ""))))
                .isInstanceOf(AssertionError.class).hasMessageContaining("version");
    }

    @Test
    void formatsAreAsserted() {
        String place = """
                {"id": "not-a-uuid", "label": "home", "name": "Home", "location": {"lat": 12.9, "lon": 77.6},
                 "created_at": "2026-10-01T10:00:00Z"}
                """;
        assertThatThrownBy(() -> OpenApiContract.assertConforms("POST", "/v1/riders/me/places",
                response(201, JSON_HEADERS, place))).isInstanceOf(AssertionError.class)
                .hasMessageContaining("uuid");
    }

    @Test
    void aBodyWhereNoneIsDocumentedFails() {
        assertThatThrownBy(() -> OpenApiContract.assertConforms("DELETE", "/v1/riders/me/places/{place_id}",
                response(204, Map.of("X-Request-Id", List.of("req_1")), "{}")))
                .isInstanceOf(AssertionError.class).hasMessageContaining("no body");
    }

    @Test
    void anEventWithAnUndeclaredPayloadPropertyFails() {
        String envelope = """
                {"event_id": "0199a3f0-7d01-7e02-8f03-a1b2c3d4e5f6", "event_type": "DriverVerified", "event_version": 1,
                 "aggregate_type": "driver", "aggregate_id": "0199a3d2-9e8f-7c6b-a5d4-c3b2a1f0e9d8",
                 "aggregate_version": 1, "occurred_at": "2026-10-01T10:00:00Z", "producer": "driver/api",
                 "correlation_id": "req_1",
                 "payload": {"driver_id": "0199a3d2-9e8f-7c6b-a5d4-c3b2a1f0e9d8", "city_id": "blr",
                             "verified_at": "2026-10-01T10:00:00Z",
                             "verified_by": "0199a300-0000-7000-8000-0000000000aa"%s}}
                """;
        JsonMapper json = JsonMapper.builder().build();

        assertThatCode(() -> EventContract.assertConforms(json.readTree(envelope.formatted(""))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> EventContract.assertConforms(json.readTree(envelope.formatted(", \"phone\": \"+91\""))))
                .isInstanceOf(AssertionError.class).hasMessageContaining("phone");
    }

    private static HttpResponse<String> response(int status, Map<String, List<String>> headers, String body) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public HttpRequest request() {
                return HttpRequest.newBuilder(uri()).build();
            }

            @Override
            public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(headers, (name, value) -> true);
            }

            @Override
            public String body() {
                return body;
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return URI.create("http://localhost/");
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }
}

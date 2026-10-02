package com.ridehailing.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.Idempotency;
import com.ridehailing.support.Effects;
import com.ridehailing.support.IdempotencyProbeController;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.PlatformTables;
import com.ridehailing.support.Postgis;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The HTTP side of LLD §5.1: replay header, problem codes, and a reformatted body counting as the same request. */
class IdempotencyHttpTests extends IntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        PlatformTables.reset(jdbc);
        Effects.reset(jdbc);
    }

    @Test
    void aReplayCarriesTheReplayedHeaderAndTheSameBodyAndLocation() {
        HttpResponse<String> first = post("key-1", "{\"amount\": 5}");
        HttpResponse<String> replay = post("key-1", "{ \"amount\" : 5 }");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.headers().firstValue(Idempotency.REPLAYED_HEADER)).isEmpty();
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue(Idempotency.REPLAYED_HEADER)).hasValue("true");
        assertThat(replay.headers().firstValue("Location")).isEqualTo(first.headers().firstValue("Location"));
        assertThat(json(replay)).isEqualTo(json(first));
        assertThat(Effects.count(jdbc, IdempotencyProbeController.EFFECT, "key-1")).isEqualTo(1);
    }

    @Test
    void aReusedKeyIsAn422Problem() {
        post("key-1", "{\"amount\": 5}");

        HttpResponse<String> reused = post("key-1", "{\"amount\": 6}");

        assertThat(reused.statusCode()).isEqualTo(422);
        assertThat(reused.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(json(reused).get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void aMissingKeyIsA400Problem() {
        HttpResponse<String> response = postJson(IdempotencyProbeController.PATH, Map.of(), "{\"amount\": 5}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void aMalformedKeyIsAValidationProblemNamingTheHeader() {
        HttpResponse<String> response = post("x".repeat(256), "{\"amount\": 5}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(json(response).get("errors").get(0).get("field").asString()).isEqualTo(Idempotency.HEADER);
    }

    @Test
    void aKeyStillRunningIsA409WithRetryAfter() throws Exception {
        // Hold the key's row lock as a running command would, by inserting it in an open transaction.
        try (var connection = Postgis.connection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO platform.idempotency_keys (principal, key, request_hash, response_status, expires_at)
                    VALUES ('tester', 'key-1', '\\x00', 0, now() + interval '1 hour')
                    """)) {
                statement.executeUpdate();
            }

            HttpResponse<String> response = post("key-1", "{\"amount\": 5}");

            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.headers().firstValue("Retry-After")).hasValue("1");
            assertThat(json(response).get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_IN_PROGRESS");
            connection.rollback();
        }
    }

    private HttpResponse<String> post(String key, String body) {
        return postJson(IdempotencyProbeController.PATH, Map.of(Idempotency.HEADER, key), body);
    }
}

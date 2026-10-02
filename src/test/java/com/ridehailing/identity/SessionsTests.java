package com.ridehailing.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.support.Eventually;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.Postgis;
import com.ridehailing.support.TestUsers;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** LLD §12.2: rotation, the 10-second retry window, theft detection, expiry, logout and disabled accounts. */
class SessionsTests extends IntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void aRefreshRotatesTheTokenAndIssuesAWorkingPair() {
        JsonNode first = signIn();

        HttpResponse<String> response = refresh(refreshToken(first));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode second = json(response);
        assertThat(refreshToken(second)).isNotEqualTo(refreshToken(first));
        assertThat(getAs("Bearer " + second.get("access_token").asString(), "/test/access/rider/things/" + userId(first))
                .statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("""
                        SELECT rotated_at IS NOT NULL FROM identity.refresh_tokens
                        WHERE token_hash = sha256(convert_to(:token, 'UTF8'))
                        """)
                .param("token", refreshToken(first)).query(Boolean.class).single()).isTrue();
    }

    @Test
    void aRetryWithinTheGraceWindowReplacesThePairFromTheFirstRotation() {
        JsonNode first = signIn();
        JsonNode lostResponse = json(refresh(refreshToken(first)));

        HttpResponse<String> retry = refresh(refreshToken(first));

        assertThat(retry.statusCode()).isEqualTo(200);
        assertProblem(refresh(refreshToken(lostResponse)), 401, "REFRESH_TOKEN_INVALID");
        assertThat(refresh(refreshToken(json(retry))).statusCode()).isEqualTo(200);
    }

    @Test
    void reuseAfterTheGraceWindowRevokesTheWholeFamilyAndIsAudited() {
        JsonNode first = signIn();
        JsonNode second = json(refresh(refreshToken(first)));
        jdbc.sql("""
                        UPDATE identity.refresh_tokens SET rotated_at = now() - interval '11 seconds'
                        WHERE token_hash = sha256(convert_to(:token, 'UTF8'))
                        """)
                .param("token", refreshToken(first))
                .update();

        assertProblem(refresh(refreshToken(first)), 401, "REFRESH_TOKEN_INVALID");

        assertProblem(refresh(refreshToken(second)), 401, "REFRESH_TOKEN_INVALID");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_log
                        WHERE action = 'token.reuse_detected' AND entity_id = :userId AND actor_type = 'SYSTEM'
                        """)
                .param("userId", userId(first).toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void expiredAndUnknownRefreshTokensAreRefused() {
        JsonNode session = signIn();
        jdbc.sql("""
                        UPDATE identity.refresh_tokens SET expires_at = now() - interval '1 second'
                        WHERE token_hash = sha256(convert_to(:token, 'UTF8'))
                        """)
                .param("token", refreshToken(session))
                .update();

        assertProblem(refresh(refreshToken(session)), 401, "REFRESH_TOKEN_INVALID");
        assertProblem(refresh("x".repeat(43)), 401, "REFRESH_TOKEN_INVALID");
    }

    @Test
    void logoutRevokesTheSessionAndAnswersTheSameForUnknownTokens() {
        JsonNode session = signIn();

        assertThat(logout(refreshToken(session)).statusCode()).isEqualTo(204);

        assertProblem(refresh(refreshToken(session)), 401, "REFRESH_TOKEN_INVALID");
        assertThat(logout("y".repeat(43)).statusCode()).isEqualTo(204);
    }

    @Test
    void aDisabledUserCantRefreshAndLosesTheSession() {
        JsonNode session = signIn();
        setStatus(userId(session), "DISABLED");

        assertProblem(refresh(refreshToken(session)), 403, "ACCOUNT_DISABLED");

        setStatus(userId(session), "ACTIVE");
        assertProblem(refresh(refreshToken(session)), 401, "REFRESH_TOKEN_INVALID");
    }

    @Test
    void concurrentRefreshesWithOneTokenLeaveExactlyOneLiveToken() throws Exception {
        JsonNode session = signIn();
        List<Future<Integer>> refreshes = new ArrayList<>();

        // Holding the token's row lock from another session lines all five refreshes up inside the database.
        try (Connection blocker = Postgis.connection(); var executor = Executors.newFixedThreadPool(5)) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("""
                    SELECT 1 FROM identity.refresh_tokens WHERE token_hash = sha256(convert_to(?, 'UTF8')) FOR UPDATE
                    """)) {
                lock.setString(1, refreshToken(session));
                lock.executeQuery().close();
            }
            for (int client = 0; client < 5; client++) {
                refreshes.add(executor.submit(() -> refresh(refreshToken(session)).statusCode()));
            }
            Eventually.within(Duration.ofSeconds(10), () -> assertThat(sessionsWaitingForALock()).isEqualTo(5));
            blocker.rollback();
            for (Future<Integer> refresh : refreshes) {
                assertThat(refresh.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        }

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM identity.refresh_tokens
                        WHERE family_id = (SELECT family_id FROM identity.refresh_tokens
                                           WHERE token_hash = sha256(convert_to(:token, 'UTF8')))
                          AND revoked_at IS NULL AND rotated_at IS NULL
                        """)
                .param("token", refreshToken(session)).query(Long.class).single()).isEqualTo(1);
    }

    private long sessionsWaitingForALock() {
        return jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()")
                .query(Long.class)
                .single();
    }

    private JsonNode signIn() {
        String phone = TestUsers.randomPhone();
        postJson("/v1/auth/otp", Map.of(), "{\"phone\": \"" + phone + "\"}");
        HttpResponse<String> response = postJson("/v1/auth/token", Map.of(),
                "{\"phone\": \"" + phone + "\", \"code\": \"" + SignInTests.CODE + "\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private HttpResponse<String> refresh(String refreshToken) {
        return postJson("/v1/auth/refresh", Map.of(), "{\"refresh_token\": \"" + refreshToken + "\"}");
    }

    private HttpResponse<String> logout(String refreshToken) {
        return postJson("/v1/auth/logout", Map.of(), "{\"refresh_token\": \"" + refreshToken + "\"}");
    }

    private void setStatus(UUID userId, String status) {
        jdbc.sql("UPDATE identity.users SET status = :status WHERE id = :id")
                .param("status", status)
                .param("id", userId)
                .update();
    }

    private static String refreshToken(JsonNode tokens) {
        return tokens.get("refresh_token").asString();
    }

    private static UUID userId(JsonNode tokens) {
        return UUID.fromString(tokens.get("user").get("id").asString());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response).get("code").asString()).isEqualTo(code);
    }
}

package com.ridehailing.identity;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.shared.Phones;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** LLD §12.1: codes are counted, expire, work once, never stored or logged in clear, and are rate-limited per phone. */
@ExtendWith(OutputCaptureExtension.class)
class SignInTests extends IntegrationTest {

    /** The test profile's fixed code. */
    static final String CODE = "123456";
    private static final String WRONG = "000000";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TestUsers users;

    @Test
    void aCodeRequestIsAcceptedAndOnlyTheCodesHmacIsKept(CapturedOutput output) {
        String phone = TestUsers.newPhone();

        HttpResponse<String> response = requestCode(phone);

        JsonNode body = assertAnswered("POST", "/v1/auth/otp", response, 202);
        assertThat(body.get("resend_after_s").asInt()).isEqualTo(30);
        assertThat(Instant.parse(body.get("expires_at").asString()))
                .isBetween(Instant.now().plus(Duration.ofMinutes(4)), Instant.now().plus(Duration.ofMinutes(6)));
        Map<String, Object> challenge = jdbc.sql("""
                        SELECT code_hmac, host(request_ip) AS ip FROM identity.otp_challenges WHERE phone = :phone
                        """)
                .param("phone", phone)
                .query()
                .singleRow();
        assertThat((byte[]) challenge.get("code_hmac")).hasSize(32);
        assertThat(challenge.get("ip")).isNotNull();
        assertThat(output).contains(Phones.mask(phone)).doesNotContain(phone);
    }

    @Test
    void aFirstSignInCreatesARiderWhoseTokenOpensRiderEndpoints() {
        String phone = TestUsers.newPhone();
        requestCode(phone);

        HttpResponse<String> response = exchange(phone, CODE);

        JsonNode tokens = assertAnswered("POST", "/v1/auth/token", response, 200);
        assertThat(tokens.get("token_type").asString()).isEqualTo("Bearer");
        assertThat(tokens.get("expires_in").asInt()).isEqualTo(900);
        assertThat(tokens.get("refresh_expires_in").asInt()).isEqualTo(30 * 24 * 3600);
        assertThat(tokens.get("user").get("roles").valueStream().map(JsonNode::asString)).containsExactly("RIDER");
        String userId = tokens.get("user").get("id").asString();
        assertThat(getAs("Bearer " + tokens.get("access_token").asString(), "/test/access/rider/things/" + userId)
                .statusCode()).isEqualTo(200);

        requestCode(phone);
        assertThat(json(exchange(phone, CODE)).get("user").get("id").asString()).isEqualTo(userId);
        assertThat(jdbc.sql("SELECT count(*) FROM identity.users WHERE phone = :phone").param("phone", phone)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void wrongCodesCountAndTheFifthEndsTheChallenge() {
        String phone = TestUsers.newPhone();
        requestCode(phone);

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertProblem(exchange(phone, WRONG), 401, "CODE_INVALID");
            assertThat(attempts(phone)).isEqualTo(attempt);
        }
        assertProblem(exchange(phone, WRONG), 429, "CODE_ATTEMPTS_EXCEEDED");

        assertProblem(exchange(phone, CODE), 401, "CODE_INVALID");
    }

    @Test
    void concurrentWrongGuessesAreCountedOneByOne() throws Exception {
        String phone = TestUsers.newPhone();
        requestCode(phone);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> guesses = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(10)) {
            for (int guess = 0; guess < 10; guess++) {
                guesses.add(executor.submit(() -> {
                    start.await();
                    return exchange(phone, WRONG).statusCode();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> guess : guesses) {
                statuses.add(guess.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsOnly(401, 429).filteredOn(status -> status == 429).hasSize(1);
        }
        assertThat(attempts(phone)).isEqualTo(5);
    }

    @Test
    void anExpiredCodeIsRefused() {
        String phone = TestUsers.newPhone();
        requestCode(phone);
        jdbc.sql("UPDATE identity.otp_challenges SET expires_at = now() - interval '1 second' WHERE phone = :phone")
                .param("phone", phone)
                .update();

        assertProblem(exchange(phone, CODE), 401, "CODE_INVALID");
    }

    @Test
    void aCodeWorksOnceAndNeverWithoutARequest() {
        String phone = TestUsers.newPhone();
        assertProblem(exchange(phone, CODE), 401, "CODE_INVALID");
        requestCode(phone);

        assertThat(exchange(phone, CODE).statusCode()).isEqualTo(200);
        assertProblem(exchange(phone, CODE), 401, "CODE_INVALID");
    }

    @Test
    void aDisabledUsersCorrectCodeIsUsedUpAndRefused() {
        TestUser user = users.create(UserRole.RIDER);
        jdbc.sql("UPDATE identity.users SET status = 'DISABLED' WHERE id = :id").param("id", user.id()).update();
        requestCode(user.phone());

        assertProblem(exchange(user.phone(), CODE), 403, "ACCOUNT_DISABLED");

        assertThat(jdbc.sql("SELECT consumed_at IS NOT NULL FROM identity.otp_challenges WHERE phone = :phone")
                .param("phone", user.phone()).query(Boolean.class).single()).isTrue();
    }

    @Test
    void aPhoneGetsFiveCodesAnHour() {
        String phone = TestUsers.newPhone();
        for (int request = 0; request < 5; request++) {
            assertThat(requestCode(phone).statusCode()).isEqualTo(202);
        }

        HttpResponse<String> limited = requestCode(phone);

        assertProblem(limited, 429, "RATE_LIMITED");
        assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())).isBetween(700L, 720L);
    }

    @Test
    void aMalformedPhoneIsAValidationProblem() {
        HttpResponse<String> response = postJson("/v1/auth/otp", Map.of(), "{\"phone\": \"98450 12345\"}");

        assertProblem(response, 400, "VALIDATION_FAILED");
        assertThat(json(response).get("errors").get(0).get("field").asString()).isEqualTo("phone");
    }

    private HttpResponse<String> requestCode(String phone) {
        return postJson("/v1/auth/otp", Map.of(), "{\"phone\": \"" + phone + "\"}");
    }

    private HttpResponse<String> exchange(String phone, String code) {
        return postJson("/v1/auth/token", Map.of(), "{\"phone\": \"" + phone + "\", \"code\": \"" + code + "\"}");
    }

    private int attempts(String phone) {
        return jdbc.sql("SELECT attempts FROM identity.otp_challenges WHERE phone = :phone")
                .param("phone", phone)
                .query(Integer.class)
                .single();
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response).get("code").asString()).isEqualTo(code);
    }
}

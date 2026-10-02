package com.ridehailing.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.IdempotentCall;
import com.ridehailing.support.Effects;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.PlatformTables;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** LLD §5.1: one effect per key, replays of the stored response, and the 400, 409 and 422 cases. */
class IdempotencyTests extends IntegrationTest {

    private static final String OPERATION = "POST /v1/rides/0199a3f0-7c2e-7a41-9b3d-5f2e8c1d4a10/cancel";
    private static final String EFFECT = "command";

    @Autowired
    private Idempotency idempotency;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate template;

    @BeforeEach
    void reset() {
        PlatformTables.reset(jdbc);
        Effects.reset(jdbc);
    }

    @Test
    void aRepeatedCallReplaysTheStoredResponseWithoutRunningTheCommandAgain() {
        IdempotentCall call = call("rider-1", "key-1", Map.of("reason", "changed plans"));

        ResponseEntity<?> first = idempotency.execute(call, command("key-1", HttpStatus.CREATED));
        ResponseEntity<?> second = idempotency.execute(call, command("key-1", HttpStatus.CREATED));

        assertThat(Effects.count(jdbc, EFFECT, "key-1")).isEqualTo(1);
        assertThat(first.getHeaders().containsHeader(Idempotency.REPLAYED_HEADER)).isFalse();
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst(Idempotency.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(second.getHeaders().getLocation()).isEqualTo(URI.create("/v1/things/key-1"));
        assertThat(((JsonNode) second.getBody()).get("ride_status").asString()).isEqualTo("CANCELLED");
    }

    @Test
    void theSameKeyWithADifferentBodyIsRejectedAndNothingRuns() {
        idempotency.execute(call("rider-1", "key-1", Map.of("reason", "a")), command("key-1", HttpStatus.OK));

        assertThatThrownBy(() -> idempotency.execute(call("rider-1", "key-1", Map.of("reason", "b")),
                command("key-1", HttpStatus.OK)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });
        assertThat(Effects.count(jdbc, EFFECT, "key-1")).isEqualTo(1);
    }

    @Test
    void theSameKeyOnAnotherPathIsADifferentRequest() {
        Map<String, Object> body = Map.of("reason", "a");
        idempotency.execute(call("rider-1", "key-1", body), command("key-1", HttpStatus.OK));

        assertThatThrownBy(() -> idempotency.execute(
                new IdempotentCall("rider-1", "key-1", OPERATION.replace("0199a3f0", "0299a3f0"), body),
                command("key-1", HttpStatus.OK)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void mapOrderDoesNotChangeTheRequest() {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("a", 1);
        ab.put("b", 2);
        Map<String, Object> ba = new LinkedHashMap<>();
        ba.put("b", 2);
        ba.put("a", 1);

        idempotency.execute(call("rider-1", "key-1", ab), command("key-1", HttpStatus.OK));
        ResponseEntity<?> replay = idempotency.execute(call("rider-1", "key-1", ba), command("key-1", HttpStatus.OK));

        assertThat(replay.getHeaders().getFirst(Idempotency.REPLAYED_HEADER)).isEqualTo("true");
    }

    @Test
    void keysArePerPrincipal() {
        idempotency.execute(call("rider-1", "shared-key", Map.of()), command("rider-1", HttpStatus.OK));
        idempotency.execute(call("rider-2", "shared-key", Map.of()), command("rider-2", HttpStatus.OK));

        assertThat(Effects.count(jdbc, EFFECT, "rider-1")).isEqualTo(1);
        assertThat(Effects.count(jdbc, EFFECT, "rider-2")).isEqualTo(1);
    }

    @Test
    void aFailedCommandReleasesTheKeyAndItsWritesSoARetryRunsIt() {
        IdempotentCall call = call("rider-1", "key-1", Map.of());

        assertThatThrownBy(() -> idempotency.execute(call, () -> {
            Effects.record(jdbc, EFFECT, "key-1");
            throw new IllegalStateException("crash after the write");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(Effects.count(jdbc, EFFECT, "key-1")).isZero();

        ResponseEntity<?> retry = idempotency.execute(call, command("key-1", HttpStatus.OK));

        assertThat(retry.getHeaders().containsHeader(Idempotency.REPLAYED_HEADER)).isFalse();
        assertThat(Effects.count(jdbc, EFFECT, "key-1")).isEqualTo(1);
    }

    @Test
    void aRejectionThatChangedStateIsStoredAndReplayedExactly() {
        IdempotentCall call = call("driver-1", "pin-attempt", Map.of("pin", "0000"));
        Supplier<ResponseEntity<?>> wrongPin = () -> {
            Effects.record(jdbc, EFFECT, "pin-attempt");
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                    .header("Content-Type", "application/problem+json")
                    .body(Map.of("code", "WRONG_PIN", "attempts_left", 4));
        };

        idempotency.execute(call, wrongPin);
        ResponseEntity<?> replay = idempotency.execute(call, wrongPin);

        assertThat(Effects.count(jdbc, EFFECT, "pin-attempt")).isEqualTo(1);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(replay.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(((JsonNode) replay.getBody()).get("attempts_left").asInt()).isEqualTo(4);
    }

    @Test
    void aSecondCallWhileTheFirstRunsGets409WithRetryAfter() throws Exception {
        IdempotentCall call = call("rider-1", "key-1", Map.of());
        CountDownLatch firstHoldsTheKey = new CountDownLatch(1);
        CountDownLatch finishFirst = new CountDownLatch(1);
        CompletableFuture<ResponseEntity<?>> first = CompletableFuture.supplyAsync(() -> idempotency.execute(call, () -> {
            firstHoldsTheKey.countDown();
            await(finishFirst);
            return command("key-1", HttpStatus.CREATED).get();
        }));
        assertThat(firstHoldsTheKey.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        assertThatThrownBy(() -> idempotency.execute(call, command("key-1", HttpStatus.CREATED)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_IN_PROGRESS");
                    assertThat(e.retryAfter()).hasValue(Duration.ofSeconds(1));
                });
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));

        finishFirst.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<?> replay = idempotency.execute(call, command("key-1", HttpStatus.CREATED));
        assertThat(replay.getHeaders().getFirst(Idempotency.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(Effects.count(jdbc, EFFECT, "key-1")).isEqualTo(1);
    }

    @Test
    void anExpiredKeyCanBeUsedAgain() {
        IdempotentCall call = call("rider-1", "key-1", Map.of());
        idempotency.execute(call, command("key-1", HttpStatus.OK));
        jdbc.sql("UPDATE platform.idempotency_keys SET expires_at = now() - interval '1 second'").update();

        ResponseEntity<?> again = idempotency.execute(call, command("key-1", HttpStatus.OK));

        assertThat(again.getHeaders().containsHeader(Idempotency.REPLAYED_HEADER)).isFalse();
        assertThat(Effects.count(jdbc, EFFECT, "key-1")).isEqualTo(2);
    }

    @Test
    void aMissingKeyIsRequired() {
        assertThatThrownBy(() -> idempotency.execute(call("rider-1", null, Map.of()), command("x", HttpStatus.OK)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
                });
    }

    @Test
    void aMalformedKeyFailsValidationNamingTheHeader() {
        for (String key : new String[] {"has space", "x".repeat(256), "naïve"}) {
            assertThatThrownBy(() -> idempotency.execute(call("rider-1", key, Map.of()), command("x", HttpStatus.OK)))
                    .as(key)
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
                        assertThat(e.properties().get("errors").toString()).contains(Idempotency.HEADER);
                    });
        }
        assertThat(Effects.count(jdbc, EFFECT, "x")).isZero();
    }

    @Test
    void theCommandMustNotJoinAnOuterTransaction() {
        assertThatThrownBy(() -> template.executeWithoutResult(status ->
                idempotency.execute(call("rider-1", "key-1", Map.of()), command("key-1", HttpStatus.OK))))
                .isInstanceOf(IllegalStateException.class);
    }

    private static IdempotentCall call(String principal, String key, Map<String, Object> body) {
        return new IdempotentCall(principal, key, OPERATION, body);
    }

    private Supplier<ResponseEntity<?>> command(String effect, HttpStatus status) {
        return () -> {
            Effects.record(jdbc, EFFECT, effect);
            return ResponseEntity.status(status)
                    .location(URI.create("/v1/things/" + effect))
                    .body(new Outcome("CANCELLED"));
        };
    }

    record Outcome(String rideStatus) {
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

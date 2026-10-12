package com.ridehailing.platform.outbox;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.EventRecovery;
import com.ridehailing.platform.LogContext;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.Transactions;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.Effects;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.PlatformTables;
import com.ridehailing.support.ScriptedConsumer;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(EventRecoveryTests.Consumers.class)
class EventRecoveryTests extends IntegrationTest {

    private static final String CONSUMER = "test.recovery";
    private static final String LIST_PATH = "/v1/ops/dead-letters";
    private static final String REDRIVE_PATH = LIST_PATH + "/{dead_letter_id}/redrive";
    private static final String REPLAYS_PATH = "/v1/ops/replays";
    private static final String REPLAY_PATH = REPLAYS_PATH + "/{replay_id}";
    private static final String RETRY_PATH = REPLAY_PATH + "/retry";
    private static final Instant FROM = Instant.parse("2026-10-01T08:00:00Z");
    private static final Instant TO = FROM.plusSeconds(10);
    private static final UUID DRIVER = UUID.fromString("0199a3f0-0000-7000-8000-000000000062");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    TestUsers users;

    @Autowired
    Outbox outbox;

    @Autowired
    Transactions transactions;

    @Autowired
    EventRecovery recovery;

    @Autowired
    EventReplayJob replayJob;

    @Autowired
    EventConsumers consumers;

    @Autowired
    ConsumerDelivery delivery;

    @Autowired
    MeterRegistry metrics;

    @Autowired
    @Qualifier("recoveryConsumer")
    ScriptedConsumer consumer;

    private TestUser ops;
    private long offset;

    @BeforeEach
    void reset() {
        PlatformTables.reset(jdbc);
        Effects.reset(jdbc);
        consumer.reset();
        ops = users.create(UserRole.OPS);
    }

    @Test
    void deadLettersArePagedNewestFirstAndFilteredWithoutExposingRawPayloads() {
        Letter first = letter(CONSUMER, false);
        Letter second = letter("test.second", false);
        Letter third = letter(CONSUMER, false);

        JsonNode page = assertAnswered("GET", LIST_PATH, getAs(ops.authorization(), LIST_PATH + "?limit=2"), 200);
        assertThat(ids(page)).containsExactly(third.id(), second.id());
        JsonNode last = assertAnswered("GET", LIST_PATH, getAs(ops.authorization(), LIST_PATH + "?limit=2&cursor="
                + page.get("next_cursor").asString()), 200);
        assertThat(ids(last)).containsExactly(first.id());
        assertThat(last.has("next_cursor")).isFalse();
        JsonNode filtered = assertAnswered("GET", LIST_PATH, getAs(ops.authorization(), LIST_PATH + "?consumer=" + CONSUMER), 200);
        assertThat(ids(filtered)).containsExactly(third.id(), first.id());
        JsonNode item = filtered.get("items").get(0);
        assertThat(item.get("event_type").asString()).isEqualTo("DriverVerified");
        assertThat(item.get("event_id").asString()).isEqualTo(third.eventId().toString());
        assertThat(item.has("record_value")).isFalse();
        assertThat(item.has("redriven_at")).isFalse();
        assertProblem("GET", LIST_PATH, getAs(ops.authorization(), LIST_PATH + "?cursor=bad"), 400, "VALIDATION_FAILED");
        assertProblem("GET", LIST_PATH, getAs(ops.authorization(), LIST_PATH + "?limit=101"), 400, "VALIDATION_FAILED");
    }

    @Test
    void redriveCommitsTheEffectInboxResolutionAndAuditOnceAndReplaysTheStoredResponse() {
        Letter letter = letter(CONSUMER, false);
        String key = UUID.randomUUID().toString();
        HttpResponse<String> first = redrive(letter.id(), key);
        JsonNode resolved = assertAnswered("POST", REDRIVE_PATH, first, 200);
        HttpResponse<String> replay = redrive(letter.id(), key);

        assertAnswered("POST", REDRIVE_PATH, replay, 200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(resolved.has("redriven_at")).isTrue();
        assertThat(Effects.count(jdbc, CONSUMER, letter.eventId().toString())).isEqualTo(1);
        assertThat(inbox(letter.eventId())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_log WHERE entity_type = 'dead_letter' AND entity_id = :id")
                .param("id", letter.id().toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(assertAnswered("POST", REDRIVE_PATH, redrive(letter.id(), UUID.randomUUID().toString()), 200)).isEqualTo(resolved);
        assertThat(Effects.count(jdbc, CONSUMER, letter.eventId().toString())).isEqualTo(1);
        assertThat(get(managementPort, "/actuator/prometheus").body()).contains("dead_letters_pending", "dead_letters_redriven_total");
    }

    @Test
    void aRejectedRedriveRollsBackAllEffectsAndCanBeRetriedAfterTheHandlerIsFixed() {
        Letter letter = letter(CONSUMER, false);
        consumer.afterEffect(event -> {
            throw new IllegalStateException("consumer is still broken");
        });

        assertProblem("POST", REDRIVE_PATH, redrive(letter.id(), UUID.randomUUID().toString()), 409, "DEAD_LETTER_REDRIVE_FAILED");

        assertThat(inbox(letter.eventId())).isZero();
        assertThat(Effects.count(jdbc, CONSUMER, letter.eventId().toString())).isZero();
        assertThat(jdbc.sql("SELECT resolved_at IS NULL FROM platform.dead_letters WHERE id = :id")
                .param("id", letter.id()).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_log WHERE entity_type = 'dead_letter' AND entity_id = :id")
                .param("id", letter.id().toString()).query(Long.class).single()).isZero();
        consumer.reset();
        assertAnswered("POST", REDRIVE_PATH, redrive(letter.id(), UUID.randomUUID().toString()), 200);
        assertThat(Effects.count(jdbc, CONSUMER, letter.eventId().toString())).isEqualTo(1);
    }

    @Test
    void malformedRecordsAndMissingOrUnsubscribedConsumersRemainUnresolved() {
        Letter malformed = letter(CONSUMER, true);
        Letter missing = letter("unknown.consumer", false);
        Letter unsubscribed = letter("test.second", false);

        assertProblem("POST", REDRIVE_PATH, redrive(malformed.id(), UUID.randomUUID().toString()), 409, "DEAD_LETTER_INVALID");
        assertProblem("POST", REDRIVE_PATH, redrive(missing.id(), UUID.randomUUID().toString()), 409, "CONSUMER_UNAVAILABLE");
        assertProblem("POST", REDRIVE_PATH, redrive(unsubscribed.id(), UUID.randomUUID().toString()), 409, "CONSUMER_NOT_SUBSCRIBED");
        assertProblem("POST", REDRIVE_PATH, redrive(UUID.randomUUID(), UUID.randomUUID().toString()), 404, "NOT_FOUND");
        assertThat(jdbc.sql("SELECT count(*) FROM platform.dead_letters WHERE resolved_at IS NOT NULL").query(Long.class).single()).isZero();
        assertThat(Effects.refs(jdbc, CONSUMER)).isEmpty();
    }

    @Test
    void onlyStaffCanListAndRedriveAndCommandsRequireAnIdempotencyKey() {
        Letter letter = letter(CONSUMER, false);
        String path = LIST_PATH + "/" + letter.id() + "/redrive";
        TestUser rider = users.create(UserRole.RIDER);
        TestUser admin = users.create(UserRole.ADMIN);

        assertProblem("GET", LIST_PATH, get(port, LIST_PATH), 401, "UNAUTHENTICATED");
        assertProblem("GET", LIST_PATH, getAs(rider.authorization(), LIST_PATH), 403, "FORBIDDEN");
        assertProblem("POST", REDRIVE_PATH, postJson(path, Map.of("Authorization", rider.authorization(),
                Idempotency.HEADER, UUID.randomUUID().toString()), "{}"), 403, "FORBIDDEN");
        assertAnswered("POST", REDRIVE_PATH, postJson(path, Map.of("Authorization", ops.authorization()), "{}"), 400);
        assertAnswered("POST", REDRIVE_PATH, postJson(path, Map.of("Authorization", admin.authorization(),
                Idempotency.HEADER, UUID.randomUUID().toString()), "{}"), 200);
    }

    @Test
    void replayAcceptanceFreezesTheSubscribedTimeRangeAndSurvivesOutboxDeletion() {
        append("DriverVerified", FROM.minusSeconds(1), 1);
        UUID first = append("DriverVerified", FROM, 2);
        UUID second = append("DriverVerified", FROM.plusSeconds(5), 3);
        append("DriverVerified", TO, 4);
        append("DriverSuspended", FROM.plusSeconds(5), 5);
        String key = UUID.randomUUID().toString();
        String body = replayBody(CONSUMER, FROM, TO, false);

        HttpResponse<String> response = startReplay(body, key);
        JsonNode accepted = assertAnswered("POST", REPLAYS_PATH, response, 202);
        UUID id = UUID.fromString(accepted.get("id").asString());
        assertThat(accepted.get("status").asString()).isEqualTo("RUNNING");
        assertThat(accepted.get("events").asLong()).isZero();
        assertThat(accepted.get("total_events").asLong()).isEqualTo(2);
        assertThat(accepted.get("reprocess").asBoolean()).isFalse();
        assertThat(assertAnswered("GET", REPLAY_PATH, getAs(ops.authorization(), REPLAYS_PATH + "/" + id), 200)).isEqualTo(accepted);
        assertThat(startReplay(body, key).body()).isEqualTo(response.body());
        assertThat(jdbc.sql("SELECT count(*) FROM platform.event_replays").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_log WHERE entity_type = 'event_replay' AND entity_id = :id")
                .param("id", id.toString()).query(Long.class).single()).isEqualTo(1);

        jdbc.sql("DELETE FROM platform.outbox").update();
        append("DriverVerified", FROM.plusSeconds(6), 6);

        assertThat(jdbc.sql("SELECT envelope->>'event_id' FROM platform.event_replay_items WHERE replay_id = :id ORDER BY outbox_id")
                .param("id", id).query(String.class).list()).containsExactly(first.toString(), second.toString());
        assertAnswered("POST", REPLAYS_PATH, startReplay(replayBody(CONSUMER, FROM, TO, true), key), 422);
        assertThat(Effects.refs(jdbc, CONSUMER)).isEmpty();
    }

    @Test
    void replaysValidateConsumersAndRangesAndAreRestrictedToStaff() {
        for (String body : List.of(replayBody("unknown", FROM, TO, false), replayBody(CONSUMER, TO, FROM, false),
                replayBody(CONSUMER, FROM, FROM, false), "{\"consumer\":\"" + CONSUMER + "\"}")) {
            assertAnswered("POST", REPLAYS_PATH, startReplay(body, UUID.randomUUID().toString()), 400);
        }
        TestUser rider = users.create(UserRole.RIDER);
        assertProblem("POST", REPLAYS_PATH, postJson(REPLAYS_PATH, Map.of("Authorization", rider.authorization(),
                Idempotency.HEADER, UUID.randomUUID().toString()), replayBody(CONSUMER, FROM, TO, false)), 403, "FORBIDDEN");
        assertProblem("GET", REPLAY_PATH, getAs(rider.authorization(), REPLAYS_PATH + "/" + UUID.randomUUID()), 403, "FORBIDDEN");
        assertProblem("GET", REPLAY_PATH, getAs(ops.authorization(), REPLAYS_PATH + "/" + UUID.randomUUID()), 404, "NOT_FOUND");
        assertThat(jdbc.sql("SELECT count(*) FROM platform.event_replays").query(Long.class).single()).isZero();
    }

    @Test
    void normalReplaySkipsHandledEventsWhileExplicitReprocessingOnlyClearsTheTargetConsumer() {
        UUID first = append("DriverVerified", FROM, 1);
        UUID second = append("DriverVerified", FROM.plusSeconds(1), 2);
        UUID initial = recovery.startReplay(CONSUMER, FROM, TO, false).id();
        replayJob.run();
        assertThat(recovery.replay(initial).status()).isEqualTo("DONE");

        UUID deduplicated = recovery.startReplay(CONSUMER, FROM, TO, false).id();
        replayJob.run();
        assertThat(recovery.replay(deduplicated).events()).isEqualTo(2);
        assertThat(Effects.refs(jdbc, CONSUMER)).containsExactly(first.toString(), second.toString());
        jdbc.sql("INSERT INTO platform.inbox (consumer, event_id) VALUES ('test.second', :id)").param("id", first).update();
        UUID reprocess = recovery.startReplay(CONSUMER, FROM, TO, true).id();
        replayJob.run();

        assertThat(recovery.replay(reprocess).status()).isEqualTo("DONE");
        assertThat(Effects.count(jdbc, CONSUMER, first.toString())).isEqualTo(2);
        assertThat(Effects.count(jdbc, CONSUMER, second.toString())).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM platform.inbox WHERE consumer = 'test.second' AND event_id = :id")
                .param("id", first).query(Long.class).single()).isEqualTo(1);
        assertThat(get(managementPort, "/actuator/prometheus").body()).contains("event_replays_running", "event_replays_completed_total",
                "event_replays_failures_total", "event_replay_events_total");
    }

    @Test
    void boundedReplayResumesInANewWorkerFromSnapshotsAfterTheOutboxIsGone() {
        List<UUID> events = new ArrayList<>();
        for (int index = 0; index <= EventReplayJob.BATCH_SIZE; index++) {
            events.add(append("DriverVerified", FROM, index));
        }
        UUID id = recovery.startReplay(CONSUMER, FROM, TO, false).id();
        jdbc.sql("DELETE FROM platform.outbox").update();

        assertThat(replayJob.processNext()).isTrue();
        assertThat(recovery.replay(id).status()).isEqualTo("RUNNING");
        assertThat(recovery.replay(id).events()).isEqualTo(EventReplayJob.BATCH_SIZE);
        EventReplayJob restarted = new EventReplayJob(jdbc, mapper, consumers, delivery, transactions, metrics);
        assertThat(restarted.processNext()).isTrue();

        assertThat(recovery.replay(id).status()).isEqualTo("DONE");
        assertThat(recovery.replay(id).events()).isEqualTo(events.size());
        assertThat(Effects.refs(jdbc, CONSUMER)).containsExactlyElementsOf(events.stream().map(UUID::toString).toList());
        assertThat(jdbc.sql("SELECT count(*) FROM platform.event_replay_items").query(Long.class).single()).isZero();
        assertThat(restarted.processNext()).isFalse();
    }

    @Test
    void failedReprocessingRestoresTheInboxAndAnExplicitRetryUsesTheRetainedSnapshot() {
        UUID first = append("DriverVerified", FROM, 1);
        UUID second = append("DriverVerified", FROM.plusSeconds(1), 2);
        recovery.startReplay(CONSUMER, FROM, TO, false);
        replayJob.run();
        List<OffsetDateTime> processed = inboxTimes();
        UUID id = recovery.startReplay(CONSUMER, FROM, TO, true).id();
        consumer.afterEffect(event -> {
            if (event.eventId().equals(second)) {
                throw new IllegalStateException("reprocess failure");
            }
        });

        replayJob.run();

        JsonNode failed = assertAnswered("GET", REPLAY_PATH, getAs(ops.authorization(), REPLAYS_PATH + "/" + id), 200);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("events").asLong()).isZero();
        assertThat(failed.get("error").asString()).contains("reprocess failure");
        assertThat(inboxTimes()).isEqualTo(processed);
        assertThat(Effects.refs(jdbc, CONSUMER)).containsExactly(first.toString(), second.toString());
        assertThat(jdbc.sql("SELECT count(*) FROM platform.event_replay_items WHERE replay_id = :id")
                .param("id", id).query(Long.class).single()).isEqualTo(2);
        jdbc.sql("DELETE FROM platform.outbox").update();
        consumer.reset();
        String key = UUID.randomUUID().toString();
        HttpResponse<String> retry = retryReplay(id, key);
        JsonNode accepted = assertAnswered("POST", RETRY_PATH, retry, 202);
        assertThat(accepted.get("status").asString()).isEqualTo("RUNNING");
        assertThat(accepted.has("error")).isFalse();
        assertThat(retryReplay(id, key).body()).isEqualTo(retry.body());
        replayJob.run();

        assertThat(recovery.replay(id).status()).isEqualTo("DONE");
        assertThat(Effects.count(jdbc, CONSUMER, first.toString())).isEqualTo(2);
        assertThat(Effects.count(jdbc, CONSUMER, second.toString())).isEqualTo(2);
        assertThat(jdbc.sql("SELECT generation FROM platform.event_replays WHERE id = :id").param("id", id).query(Long.class).single())
                .isEqualTo(1);
        assertProblem("POST", RETRY_PATH, retryReplay(id, UUID.randomUUID().toString()), 409, "REPLAY_NOT_FAILED");
        assertProblem("POST", RETRY_PATH, retryReplay(UUID.randomUUID(), UUID.randomUUID().toString()), 404, "NOT_FOUND");
    }

    @Test
    void databaseFailuresKeepReplayWorkPendingRatherThanMarkingItFailed() {
        UUID eventId = append("DriverVerified", FROM, 1);
        UUID id = recovery.startReplay(CONSUMER, FROM, TO, false).id();
        consumer.afterEffect(event -> {
            throw new org.springframework.dao.DataAccessResourceFailureException("temporary database failure");
        });

        assertThatThrownBy(replayJob::processNext).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);

        assertThat(recovery.replay(id).status()).isEqualTo("RUNNING");
        assertThat(recovery.replay(id).events()).isZero();
        assertThat(inbox(eventId)).isZero();
        assertThat(Effects.refs(jdbc, CONSUMER)).isEmpty();
        consumer.reset();
        replayJob.run();
        assertThat(recovery.replay(id).status()).isEqualTo("DONE");
        assertThat(Effects.count(jdbc, CONSUMER, eventId.toString())).isEqualTo(1);
    }

    @Test
    void overlappingWorkersSkipLockedReplayWork() throws Exception {
        UUID eventId = append("DriverVerified", FROM, 1);
        UUID id = recovery.startReplay(CONSUMER, FROM, TO, true).id();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdHandler(entered, release);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(replayJob::processNext);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(replayJob.processNext()).isFalse();
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(recovery.replay(id).status()).isEqualTo("DONE");
        assertThat(Effects.count(jdbc, CONSUMER, eventId.toString())).isEqualTo(1);
    }

    @Test
    void concurrentRedrivesResolveTheSameLetterOnce() throws Exception {
        Letter letter = letter(CONSUMER, false);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdHandler(entered, release);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> recovery.redrive(letter.id()));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> recovery.redrive(letter.id()));
                await().atMost(Duration.ofSeconds(1)).until(() -> jdbc.sql("""
                        SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'
                        """).query(Long.class).single() > 0);
                release.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
        assertThat(Effects.count(jdbc, CONSUMER, letter.eventId().toString())).isEqualTo(1);
    }

    @Test
    void anEmptyReplayFinishesAndOmittedReprocessDefaultsToFalse() {
        String body = mapper.writeValueAsString(Map.of("consumer", CONSUMER, "from", FROM, "to", TO));
        JsonNode accepted = assertAnswered("POST", REPLAYS_PATH, startReplay(body, UUID.randomUUID().toString()), 202);
        UUID id = UUID.fromString(accepted.get("id").asString());

        replayJob.run();

        assertThat(recovery.replay(id).status()).isEqualTo("DONE");
        assertThat(recovery.replay(id).reprocess()).isFalse();
        assertThat(recovery.replay(id).events()).isZero();
        assertThat(recovery.replay(id).totalEvents()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"progress", "retry"})
    void aLateFailureCannotOverwriteNewerProgressOrARetryGeneration(String change) throws Exception {
        for (int index = 0; index <= EventReplayJob.BATCH_SIZE; index++) {
            append("DriverVerified", FROM, index);
        }
        UUID id = recovery.startReplay(CONSUMER, FROM, TO, true).id();
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch reportFailure = new CountDownLatch(1);
        consumer.afterEffect(event -> {
            throw new IllegalStateException("old worker failure");
        });
        JdbcClient delayed = spy(jdbc);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("SET status = 'FAILED'")) {
                failed.countDown();
                awaitRelease(reportFailure);
            }
            return invocation.callRealMethod();
        }).when(delayed).sql(anyString());
        EventReplayJob oldWorker = new EventReplayJob(delayed, mapper, consumers, delivery, transactions, metrics);
        double failuresBefore = metrics.counter("event_replays.failures").count();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var old = executor.submit(oldWorker::processNext);
            try {
                assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
                consumer.reset();
                if (change.equals("progress")) {
                    assertThat(replayJob.processNext()).isTrue();
                    assertThat(recovery.replay(id).events()).isEqualTo(EventReplayJob.BATCH_SIZE);
                } else {
                    jdbc.sql("UPDATE platform.event_replays SET status = 'FAILED', error = 'newer failure' WHERE id = :id")
                            .param("id", id).update();
                    recovery.retryReplay(id);
                }
            } finally {
                reportFailure.countDown();
            }
            assertThat(old.get(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(recovery.replay(id).status()).isEqualTo("RUNNING");
        assertThat(recovery.replay(id).error()).isNull();
        assertThat(metrics.counter("event_replays.failures").count()).isEqualTo(failuresBefore);
        replayJob.run();
        replayJob.run();
        assertThat(recovery.replay(id).status()).isEqualTo("DONE");
        assertThat(Effects.refs(jdbc, CONSUMER)).hasSize(EventReplayJob.BATCH_SIZE + 1).doesNotHaveDuplicates();
    }

    private void holdHandler(CountDownLatch entered, CountDownLatch release) {
        consumer.afterEffect(event -> {
            entered.countDown();
            awaitRelease(release);
        });
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test handler gate timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private List<OffsetDateTime> inboxTimes() {
        return jdbc.sql("SELECT processed_at FROM platform.inbox WHERE consumer = :consumer ORDER BY event_id")
                .param("consumer", CONSUMER).query(OffsetDateTime.class).list();
    }

    private HttpResponse<String> retryReplay(UUID id, String key) {
        return postJson(REPLAYS_PATH + "/" + id + "/retry", Map.of("Authorization", ops.authorization(), Idempotency.HEADER, key), "{}");
    }

    private UUID append(String type, Instant occurredAt, long version) {
        transactions.run(() -> LogContext.run(Map.of(LogContext.ROLE, "worker"), () -> outbox.append(
            DomainEvent.of(type, 1, "driver", DRIVER, version, new ReplayEvent(DRIVER, DRIVER, occurredAt)))));
        UUID id = jdbc.sql("SELECT event_id FROM platform.outbox ORDER BY id DESC LIMIT 1").query(UUID.class).single();
        jdbc.sql("UPDATE platform.outbox SET occurred_at = :occurred WHERE event_id = :id")
                .param("occurred", occurredAt.atOffset(java.time.ZoneOffset.UTC)).param("id", id).update();
        return id;
    }

    private String replayBody(String target, Instant from, Instant to, boolean reprocess) {
        return mapper.writeValueAsString(Map.of("consumer", target, "from", from, "to", to, "reprocess", reprocess));
    }

    private HttpResponse<String> startReplay(String body, String key) {
        return postJson(REPLAYS_PATH, Map.of("Authorization", ops.authorization(), Idempotency.HEADER, key), body);
    }

    private Letter letter(String target, boolean malformed) {
        UUID eventId = Ids.newId();
        String topic = "drivers.events.dlq." + target;
        byte[] value = malformed ? new byte[] {'{'} : mapper.writeValueAsBytes(Map.of(
                "event_id", eventId, "event_type", "DriverVerified", "event_version", 1,
                "aggregate_type", "driver", "aggregate_id", DRIVER, "aggregate_version", 1,
                "occurred_at", Instant.now(), "producer", "driver", "correlation_id", "recovery-test",
                "payload", Map.of("driver_id", DRIVER, "verified_by", DRIVER, "verified_at", Instant.now())));
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(topic, 0, offset++, DRIVER.toString().getBytes(StandardCharsets.UTF_8), value);
        record.headers().add("event_id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event_type", "DriverVerified".getBytes(StandardCharsets.UTF_8));
        record.headers().add("event_version", "1".getBytes(StandardCharsets.UTF_8));
        new KafkaDeadLetterListener(jdbc, mapper, Map.of(topic, target)).onMessage(record, () -> { });
        UUID id = jdbc.sql("SELECT id FROM platform.dead_letters WHERE event_id = :id").param("id", eventId).query(UUID.class).single();
        return new Letter(id, eventId);
    }

    private HttpResponse<String> redrive(UUID id, String key) {
        return postJson(LIST_PATH + "/" + id + "/redrive", Map.of("Authorization", ops.authorization(), Idempotency.HEADER, key), "{}");
    }

    private long inbox(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM platform.inbox WHERE consumer = :consumer AND event_id = :id")
                .param("consumer", CONSUMER).param("id", eventId).query(Long.class).single();
    }

    private static List<UUID> ids(JsonNode page) {
        return page.get("items").valueStream().map(item -> UUID.fromString(item.get("id").asString())).toList();
    }

    record ReplayEvent(UUID driverId, UUID verifiedBy, Instant verifiedAt) {
    }

    record Letter(UUID id, UUID eventId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Consumers {

        @Bean
        ScriptedConsumer recoveryConsumer(JdbcClient jdbc) {
            return new ScriptedConsumer(CONSUMER, jdbc) {
                @Override
                public Set<String> eventTypes() {
                    return Set.of("DriverVerified");
                }
            };
        }
    }
}
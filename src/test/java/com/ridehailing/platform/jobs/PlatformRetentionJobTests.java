package com.ridehailing.platform.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.PlatformTables;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** LLD §5.7: only rows past their retention go, and an outbox row a re-drive still needs stays. */
class PlatformRetentionJobTests extends IntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RetentionProperties properties;

    @BeforeEach
    void reset() {
        PlatformTables.reset(jdbc);
    }

    @Test
    void deletesOnlyRowsPastTheirRetention() {
        idempotencyKey("expired", "-1 minute");
        idempotencyKey("live", "23 hours");
        outboxRow(8, true);
        UUID recentPublished = outboxRow(1, true);
        UUID oldUnpublished = outboxRow(8, false);
        UUID stillFailing = outboxRow(8, true);
        UUID redriven = outboxRow(8, true);
        failedDelivery(stillFailing, null);
        failedDelivery(redriven, "1 day");
        UUID oldResolution = outboxRow(1, true);
        failedDelivery(oldResolution, "15 days");
        inboxRow("old-consumer", 15);
        inboxRow("recent-consumer", 1);

        new PlatformRetentionJob(jdbc, properties).run();

        assertThat(strings("SELECT key FROM platform.idempotency_keys")).containsExactly("live");
        assertThat(strings("SELECT event_id::text FROM platform.outbox ORDER BY id")).containsExactly(
                recentPublished.toString(), oldUnpublished.toString(), stillFailing.toString(),
                oldResolution.toString());
        assertThat(strings("SELECT event_id::text FROM platform.failed_deliveries ORDER BY failed_at"))
                .containsExactlyInAnyOrder(stillFailing.toString(), redriven.toString());
        assertThat(strings("SELECT consumer FROM platform.inbox")).containsExactly("recent-consumer");
    }

    @Test
    void deletesInBatchesUntilABatchComesBackShort() {
        for (int index = 0; index < 5; index++) {
            idempotencyKey("expired-" + index, "-1 minute");
        }
        RetentionProperties smallBatches = new RetentionProperties(Duration.ofHours(1), 2, properties.outbox(),
                properties.inbox(), properties.failedDeliveries());

        new PlatformRetentionJob(jdbc, smallBatches).run();

        assertThat(strings("SELECT key FROM platform.idempotency_keys")).isEmpty();
    }

    private void idempotencyKey(String key, String expiresIn) {
        jdbc.sql("""
                        INSERT INTO platform.idempotency_keys (principal, key, request_hash, response_status, expires_at)
                        VALUES ('rider-1', :key, '\\x00', 200, now() + CAST(:expiresIn AS interval))
                        """)
                .param("key", key)
                .param("expiresIn", expiresIn)
                .update();
    }

    private UUID outboxRow(int daysAgo, boolean published) {
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO platform.outbox (event_id, event_type, event_version, aggregate_type, aggregate_id,
                                                     aggregate_version, partition_key, occurred_at, producer,
                                                     correlation_id, payload, published_at)
                        VALUES (:eventId, 'TestHappened', 1, 'ride', :eventId, 1, :eventId,
                                now() - make_interval(days => :daysAgo), 'platform/api', 'c', '{}',
                                CASE WHEN :published THEN now() - make_interval(days => :daysAgo) END)
                        """)
                .param("eventId", eventId)
                .param("daysAgo", daysAgo)
                .param("published", published)
                .update();
        return eventId;
    }

    private void failedDelivery(UUID eventId, String redrivenAgo) {
        jdbc.sql("""
                        INSERT INTO platform.failed_deliveries (consumer, event_id, outbox_id, attempts, last_error,
                                                                failed_at, redriven_at)
                        VALUES ('test.first', :eventId, 0, 4, 'boom', now() - interval '20 days',
                                now() - CAST(:redrivenAgo AS interval))
                        """)
                .param("eventId", eventId)
                .param("redrivenAgo", redrivenAgo)
                .update();
    }

    private void inboxRow(String consumer, int daysAgo) {
        jdbc.sql("""
                        INSERT INTO platform.inbox (consumer, event_id, processed_at)
                        VALUES (:consumer, gen_random_uuid(), now() - make_interval(days => :daysAgo))
                        """)
                .param("consumer", consumer)
                .param("daysAgo", daysAgo)
                .update();
    }

    private List<String> strings(String sql) {
        return jdbc.sql(sql).query(String.class).list();
    }
}

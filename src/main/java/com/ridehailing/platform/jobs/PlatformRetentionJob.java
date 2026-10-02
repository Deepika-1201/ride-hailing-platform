package com.ridehailing.platform.jobs;

import com.ridehailing.platform.RecurringJob;
import com.ridehailing.platform.Role;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Deletes the platform's expired rows in batches (LLD §5.7). Published outbox rows with an unresolved failed delivery
 * stay, because a re-drive reads the event from them.
 */
@Component
class PlatformRetentionJob implements RecurringJob {

    private static final Logger log = LoggerFactory.getLogger(PlatformRetentionJob.class);

    private final JdbcClient jdbc;
    private final RetentionProperties properties;

    PlatformRetentionJob(JdbcClient jdbc, RetentionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "platform-retention";
    }

    @Override
    public Role role() {
        return Role.WORKER;
    }

    @Override
    public Duration interval() {
        return properties.interval();
    }

    @Override
    public void run() {
        deleteInBatches("expired idempotency keys", """
                DELETE FROM platform.idempotency_keys WHERE (principal, key) IN (
                    SELECT principal, key FROM platform.idempotency_keys
                    WHERE expires_at < now() - make_interval(secs => :age)
                    LIMIT :batch)
                """, Duration.ZERO);
        deleteInBatches("published outbox rows", """
                DELETE FROM platform.outbox WHERE id IN (
                    SELECT o.id FROM platform.outbox o
                    WHERE o.published_at < now() - make_interval(secs => :age)
                      AND NOT EXISTS (SELECT 1 FROM platform.failed_deliveries f
                                      WHERE f.event_id = o.event_id AND f.redriven_at IS NULL)
                    LIMIT :batch)
                """, properties.outbox());
        deleteInBatches("inbox rows", """
                DELETE FROM platform.inbox WHERE (consumer, event_id) IN (
                    SELECT consumer, event_id FROM platform.inbox
                    WHERE processed_at < now() - make_interval(secs => :age)
                    LIMIT :batch)
                """, properties.inbox());
        deleteInBatches("re-driven failed deliveries", """
                DELETE FROM platform.failed_deliveries WHERE (consumer, event_id) IN (
                    SELECT consumer, event_id FROM platform.failed_deliveries
                    WHERE redriven_at < now() - make_interval(secs => :age)
                    LIMIT :batch)
                """, properties.failedDeliveries());
    }

    private void deleteInBatches(String rows, String sql, Duration age) {
        long total = 0;
        int deleted;
        do {
            deleted = jdbc.sql(sql)
                    .param("age", (double) age.toSeconds())
                    .param("batch", properties.batchSize())
                    .update();
            total += deleted;
        } while (deleted == properties.batchSize());
        if (total > 0) {
            log.info("Deleted {} {}", total, rows);
        }
    }
}

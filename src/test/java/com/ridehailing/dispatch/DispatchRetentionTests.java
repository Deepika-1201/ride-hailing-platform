package com.ridehailing.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.dispatch.app.DispatchRetention;
import com.ridehailing.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** LLD §5.7: decision records are kept 30 days, deleted in batches of 10,000. */
class DispatchRetentionTests extends IntegrationTest {

    @Autowired
    private DispatchRetention retention;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void decisionsOlderThanThirtyDaysAreDeletedInBatches() {
        UUID ride = UUID.randomUUID();
        decisions(ride, 10_001, "30 days 1 minute");
        decisions(ride, 1, "29 days 23 hours");

        int deleted = retention.purge();

        assertThat(deleted).isEqualTo(10_001);
        assertThat(jdbc.sql("SELECT count(*) FROM dispatch.decisions WHERE ride_id = :ride").param("ride", ride)
                .query(Long.class).single()).isEqualTo(1);
    }

    /** {@code count} decisions for the ride, made {@code age} ago by the database clock. */
    private void decisions(UUID ride, int count, String age) {
        jdbc.sql("""
                        INSERT INTO dispatch.decisions (id, ride_id, attempt, created_at, strategy, strategy_version,
                                                        radius_m, outcome, detail, duration_us)
                        SELECT gen_random_uuid(), :ride, attempt, now() - CAST(:age AS interval), 'nearest', '1',
                               1000, 'NO_CANDIDATES', '{}', 100
                        FROM generate_series(1, :count) AS attempt
                        """)
                .param("ride", ride)
                .param("age", age)
                .param("count", count)
                .update();
    }
}

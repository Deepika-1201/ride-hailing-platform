package com.ridehailing.audit.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.audit.db.AuditPartitions;
import com.ridehailing.platform.Transactions;
import com.ridehailing.support.IntegrationTest;
import java.time.Clock;
import java.time.YearMonth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** LLD §4.9, §5.7: partitions stay two months ahead, and partitions past the 3-year retention are dropped. */
class AuditPartitionMaintenanceTests extends IntegrationTest {

    private static final YearMonth ANCIENT = YearMonth.of(2020, 1);

    @Autowired
    private AuditPartitionMaintenance maintenance;

    @Autowired
    private AuditPartitions partitions;

    @Autowired
    private Transactions transactions;

    @Autowired
    private Clock clock;

    @AfterEach
    void dropTestPartitions() {
        transactions.run(() -> partitions.drop(twoYearsAgo()));
        transactions.run(() -> partitions.drop(ANCIENT));
    }

    @Test
    void theCurrentMonthAndTheNextTwoArePartitioned() {
        maintenance.maintain();

        YearMonth current = YearMonth.now(clock);
        assertThat(partitions.existing()).contains(current, current.plusMonths(1), current.plusMonths(2));
    }

    @Test
    void aMissingMonthIsCreatedAndOnlyPartitionsPastRetentionAreDropped() {
        YearMonth lastAhead = YearMonth.now(clock).plusMonths(2);
        transactions.run(() -> partitions.drop(lastAhead));
        transactions.run(() -> partitions.create(ANCIENT));
        transactions.run(() -> partitions.create(twoYearsAgo()));

        maintenance.maintain();

        assertThat(partitions.existing()).contains(lastAhead, twoYearsAgo()).doesNotContain(ANCIENT);
    }

    private YearMonth twoYearsAgo() {
        return YearMonth.now(clock).minusYears(2);
    }
}

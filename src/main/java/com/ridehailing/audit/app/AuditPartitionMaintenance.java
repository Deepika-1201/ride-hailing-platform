package com.ridehailing.audit.app;

import com.ridehailing.audit.db.AuditPartitions;
import com.ridehailing.platform.Transactions;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Keeps monthly audit partitions ready ahead of time and drops those past retention (LLD §4.9, §5.7). */
@Service
public class AuditPartitionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(AuditPartitionMaintenance.class);

    private final AuditPartitions partitions;
    private final Transactions transactions;
    private final Clock clock;
    private final AuditProperties properties;

    AuditPartitionMaintenance(AuditPartitions partitions, Transactions transactions, Clock clock,
            AuditProperties properties) {
        this.partitions = partitions;
        this.transactions = transactions;
        this.clock = clock;
        this.properties = properties;
    }

    public void maintain() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC));
        List<YearMonth> existing = partitions.existing();
        YearMonth current = YearMonth.from(now);
        for (int ahead = 0; ahead <= properties.partitionsAhead(); ahead++) {
            YearMonth month = current.plusMonths(ahead);
            if (!existing.contains(month)) {
                transactions.run(() -> partitions.create(month));
                log.info("Created the audit partition for {}", month);
            }
        }
        Instant cutoff = now.minus(properties.retention()).toInstant();
        for (YearMonth month : existing) {
            Instant end = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            if (!end.isAfter(cutoff)) {
                transactions.run(() -> partitions.drop(month));
                log.info("Dropped the audit partition for {}, past retention", month);
            }
        }
    }
}

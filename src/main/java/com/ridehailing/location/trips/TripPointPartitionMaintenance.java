package com.ridehailing.location.trips;

import com.ridehailing.platform.Transactions;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Keeps daily trip point partitions ready for today and the next two days, and drops points past their 90 days
 * (LLD §9.8, FR-L6).
 */
@Service
public class TripPointPartitionMaintenance {

    static final int DAYS_AHEAD = 2;
    static final Period RETENTION = Period.ofDays(90);

    private static final Logger log = LoggerFactory.getLogger(TripPointPartitionMaintenance.class);

    private final TripPointPartitions partitions;
    private final Transactions transactions;
    private final Clock clock;

    TripPointPartitionMaintenance(TripPointPartitions partitions, Transactions transactions, Clock clock) {
        this.partitions = partitions;
        this.transactions = transactions;
        this.clock = clock;
    }

    public void maintain() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<LocalDate> existing = partitions.existing();
        for (int ahead = 0; ahead <= DAYS_AHEAD; ahead++) {
            LocalDate day = today.plusDays(ahead);
            if (existing.contains(day)) {
                continue;
            }
            boolean created = transactions.execute(() -> {
                if (partitions.defaultHolds(day)) {
                    return false;
                }
                partitions.create(day);
                return true;
            });
            if (created) {
                log.info("Created the trip point partition for {}", day);
            } else {
                log.warn("Points of {} went to the default partition, so the day gets no partition of its own", day);
            }
        }
        LocalDate oldestKept = today.minus(RETENTION);
        for (LocalDate day : existing) {
            if (day.isBefore(oldestKept)) {
                transactions.run(() -> partitions.drop(day));
                log.info("Dropped the trip point partition for {}, past retention", day);
            }
        }
        int deleted = transactions.execute(() -> partitions.deleteDefaultBefore(oldestKept));
        if (deleted > 0) {
            log.info("Deleted {} trip points past retention from the default partition", deleted);
        }
    }
}

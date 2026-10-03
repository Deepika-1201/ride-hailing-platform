package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.db.AvailabilityRepository.AvailabilityRow;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.MirrorState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Copies availability changes into the live index after they commit (LLD §8.10), so a rolled-back change is never
 * mirrored. The index ignores versions older than its own, so the writes may arrive in any order; a failed one is
 * counted, and the reconciler repairs it.
 */
@Component
class LiveIndexMirror {

    private static final Logger log = LoggerFactory.getLogger(LiveIndexMirror.class);

    private final LiveIndex index;
    private final Counter failures;

    LiveIndexMirror(LiveIndex index, MeterRegistry meters) {
        this.index = index;
        this.failures = meters.counter("live.index.mirror.failures");
    }

    /** After the current transaction commits, or now if there is none. */
    void afterCommit(AvailabilityRow row) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            mirror(row);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                mirror(row);
            }
        });
    }

    /** Whether the index took the row's state, which it doesn't when it holds the same version or a newer one. */
    boolean mirror(AvailabilityRow row) {
        try {
            return index.mirror(row.cityId(), row.driverId(), state(row));
        } catch (RuntimeException e) {
            failures.increment();
            log.warn("Mirroring driver {} at version {} failed; the reconciler repairs it", row.driverId(),
                    row.version(), e);
            return false;
        }
    }

    static MirrorState state(AvailabilityRow row) {
        return new MirrorState(LiveIndex.Status.valueOf(row.status().name()), row.version(), row.category(),
                row.rideId());
    }
}

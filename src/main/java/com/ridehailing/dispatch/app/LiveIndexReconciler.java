package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.AvailabilityStatus;
import com.ridehailing.dispatch.db.AvailabilityRepository;
import com.ridehailing.dispatch.db.AvailabilityRepository.AvailabilityRow;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.MirrorState;
import com.ridehailing.location.LocationProperties;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Repairs the live index from PostgreSQL (LLD §8.10): missed mirror writes, and an index that lost its data. Every
 * write carries the row's version, so a repair can't undo a newer change that raced with it.
 */
@Service
public class LiveIndexReconciler {

    private static final Logger log = LoggerFactory.getLogger(LiveIndexReconciler.class);

    private final AvailabilityRepository availability;
    private final LiveIndex index;
    private final LiveIndexMirror mirror;
    private final LocationProperties location;

    LiveIndexReconciler(AvailabilityRepository availability, LiveIndex index, LiveIndexMirror mirror,
            LocationProperties location) {
        this.availability = availability;
        this.index = index;
        this.mirror = mirror;
        this.location = location;
    }

    /** The cities with online drivers, or with a driver whose offline tombstone may still be due. */
    public void reconcileAll() {
        for (String cityId : availability.citiesWithChanges(location.tombstoneTtl())) {
            try {
                int repaired = reconcile(cityId);
                if (repaired > 0) {
                    log.warn("Repaired {} live-index entries in city {}", repaired, cityId);
                }
            } catch (RuntimeException e) {
                log.error("Reconciling city {} failed; the next run tries again", cityId, e);
            }
        }
    }

    /** Every driver the index holds and every online driver, compared with their rows; returns the repairs. */
    int reconcile(String cityId) {
        Map<UUID, MirrorState> held = index.mirrored(cityId);
        Set<UUID> drivers = new HashSet<>(held.keySet());
        availability.online(cityId).forEach(row -> drivers.add(row.driverId()));
        if (drivers.isEmpty()) {
            return 0;
        }
        Map<UUID, AvailabilityRow> rows = availability.ofDrivers(drivers).stream()
                .collect(Collectors.toMap(AvailabilityRow::driverId, Function.identity()));
        int repaired = 0;
        for (UUID driverId : drivers) {
            AvailabilityRow row = rows.get(driverId);
            if (row == null) {
                continue;
            }
            // A driver who moved to another city is offline in this one.
            AvailabilityRow wanted = row.cityId().equals(cityId) ? row : offlineIn(cityId, row);
            if (!LiveIndexMirror.state(wanted).equals(held.get(driverId)) && mirror.mirror(wanted)) {
                repaired++;
            }
        }
        return repaired;
    }

    private static AvailabilityRow offlineIn(String cityId, AvailabilityRow row) {
        return new AvailabilityRow(row.driverId(), cityId, AvailabilityStatus.OFFLINE, null, null, null, null, 0, null,
                row.statusChangedAt(), row.version());
    }
}

package com.ridehailing.ride.app;

import com.ridehailing.ride.RideStatus;
import com.ridehailing.ride.db.FlagRepository;
import com.ridehailing.ride.db.FlagRepository.FlagKind;
import com.ridehailing.ride.db.RideRepository;
import com.ridehailing.ride.db.RideRepository.OverdueRide;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reports rides that stayed in one state longer than expected (ride lifecycle §9, LLD §7.11): a {@code STUCK} flag for
 * operations, and the gauge {@code rides_stuck{status}}, which has an alert.
 */
@Service
public class StuckRides {

    /** Ride lifecycle §9 <b>(assumed)</b>. */
    static final Map<RideStatus, Duration> THRESHOLDS = Map.of(
            RideStatus.SEARCHING, Duration.ofMinutes(4),
            RideStatus.DRIVER_ASSIGNED, Duration.ofMinutes(60),
            RideStatus.DRIVER_ARRIVED, Duration.ofMinutes(30),
            RideStatus.IN_TRIP, Duration.ofHours(6));
    static final int BATCH = 1_000;
    private static final Logger log = LoggerFactory.getLogger(StuckRides.class);

    private final RideRepository rides;
    private final FlagRepository flags;
    private final JsonMapper json;
    private final Map<RideStatus, AtomicInteger> stuck = new EnumMap<>(RideStatus.class);

    StuckRides(RideRepository rides, FlagRepository flags, JsonMapper json, MeterRegistry meters) {
        this.rides = rides;
        this.flags = flags;
        this.json = json;
        THRESHOLDS.keySet().forEach(status -> stuck.put(status,
                meters.gauge("rides.stuck", List.of(Tag.of("status", status.name())), new AtomicInteger())));
    }

    /** Answers how many rides it flagged; rides already flagged keep their open flag and aren't read again. */
    public int report() {
        int flagged = 0;
        List<OverdueRide> batch;
        do {
            batch = rides.overdueUnflagged(THRESHOLDS, BATCH);
            for (OverdueRide ride : batch) {
                flags.open(ride.rideId(), FlagKind.STUCK, json.writeValueAsString(Map.of("status", ride.status(),
                        "since", ride.since().toString())));
                log.warn("Ride {} has been {} since {}", ride.rideId(), ride.status(), ride.since());
            }
            flagged += batch.size();
        } while (batch.size() == BATCH);
        Map<RideStatus, Integer> counts = rides.overdueCounts(THRESHOLDS);
        stuck.forEach((status, gauge) -> gauge.set(counts.getOrDefault(status, 0)));
        return flagged;
    }
}

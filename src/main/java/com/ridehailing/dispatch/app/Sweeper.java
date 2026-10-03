package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.AvailabilityStatus;
import com.ridehailing.dispatch.db.AvailabilityRepository;
import com.ridehailing.dispatch.db.AvailabilityRepository.AvailabilityRow;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LocationProperties;
import com.ridehailing.platform.Transactions;
import com.ridehailing.shared.Actor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Silent drivers leave matching after 30 s and go offline after 10 min (LLD §8.9), unless the safety valve says the
 * silence is the platform's fault. The unreachable rule (T7) arrives with rides in phase 8.
 */
@Service
public class Sweeper {

    static final String SYSTEM_ACTOR = "sweeper";
    static final String IDLE_RULE = "idle";
    private static final int VALVE_MIN_DRIVERS = 5;
    private static final int VALVE_PERCENT = 10;
    private static final Logger log = LoggerFactory.getLogger(Sweeper.class);

    private final AvailabilityRepository availability;
    private final Availability service;
    private final LiveIndex index;
    private final LocationProperties location;
    private final Transactions transactions;
    private final Clock clock;
    private final Counter removed;
    private final Counter idleValve;

    Sweeper(AvailabilityRepository availability, Availability service, LiveIndex index, LocationProperties location,
            Transactions transactions, Clock clock, MeterRegistry meters) {
        this.availability = availability;
        this.service = service;
        this.index = index;
        this.location = location;
        this.transactions = transactions;
        this.clock = clock;
        this.removed = meters.counter("location.sweeper.removed");
        // Registered at zero, so an alert on its increase sees the first trip.
        this.idleValve = meters.counter("sweeper.safety.valve", "rule", IDLE_RULE);
    }

    /** Every city with online drivers, each on its own, so one city's failure doesn't stop the others. */
    public void sweepAll() {
        Instant now = clock.instant();
        for (String cityId : availability.citiesWithOnlineDrivers()) {
            try {
                sweep(cityId, now);
            } catch (RuntimeException e) {
                log.error("Sweeping city {} failed; the next run tries again", cityId, e);
            }
        }
    }

    /** Returns the drivers taken offline. */
    List<UUID> sweep(String cityId, Instant now) {
        removed.increment(index.sweep(cityId, now.minus(location.freshness())).size());
        return takeIdleOffline(cityId, now);
    }

    private List<UUID> takeIdleOffline(String cityId, Instant now) {
        List<AvailabilityRow> available = availability.online(cityId).stream()
                .filter(row -> row.status() == AvailabilityStatus.AVAILABLE)
                .toList();
        if (available.isEmpty()) {
            return List.of();
        }
        Instant idleBefore = now.minus(location.offlineAfter());
        Instant epoch = index.epoch(cityId);
        Map<UUID, Instant> seen = index.lastSeen(cityId, available.stream().map(AvailabilityRow::driverId).toList());
        List<UUID> idle = available.stream()
                .filter(row -> lastSeen(seen.get(row.driverId()), row.onlineSince(), epoch).isBefore(idleBefore))
                .map(AvailabilityRow::driverId)
                .toList();
        if (idle.isEmpty()) {
            return List.of();
        }
        if (epochTooYoung(epoch, idleBefore)) {
            return valve(cityId, "the index epoch " + epoch + " is younger than the rule's threshold", idle.size());
        }
        if (massSilence(idle.size(), available.size())) {
            return valve(cityId, idle.size() + " of " + available.size() + " available drivers are silent",
                    idle.size());
        }
        List<UUID> offline = new ArrayList<>();
        for (UUID driverId : idle) {
            try {
                if (takeOfflineIfIdle(cityId, driverId, idleBefore, epoch)) {
                    offline.add(driverId);
                }
            } catch (RuntimeException e) {
                log.error("Taking silent driver {} offline failed; the next run tries again", driverId, e);
            }
        }
        return offline;
    }

    /** Re-checks under the row's lock: the driver may have moved on, or spoken, since the city was read. */
    private boolean takeOfflineIfIdle(String cityId, UUID driverId, Instant idleBefore, Instant epoch) {
        return transactions.execute(() -> {
            AvailabilityRow locked = availability.lock(driverId).orElseThrow();
            if (locked.status() != AvailabilityStatus.AVAILABLE || !locked.cityId().equals(cityId)) {
                return false;
            }
            Instant seen = index.lastSeen(cityId, List.of(driverId)).get(driverId);
            if (!lastSeen(seen, locked.onlineSince(), epoch).isBefore(idleBefore)) {
                return false;
            }
            service.takeOffline(locked, "SILENT", Actor.system(SYSTEM_ACTOR));
            return true;
        });
    }

    private List<UUID> valve(String cityId, String why, int idle) {
        idleValve.increment();
        log.warn("Safety valve: not taking {} silent drivers offline in city {}, because {}", idle, cityId, why);
        return List.of();
    }

    /**
     * Silence starts no earlier than going online; a driver the index hasn't heard from counts as last seen at the
     * later of the index epoch and going online.
     */
    static Instant lastSeen(Instant seen, Instant onlineSince, Instant epoch) {
        Instant floor = seen != null ? seen : epoch;
        return floor.isAfter(onlineSince) ? floor : onlineSince;
    }

    /** Silence isn't meaningful yet when the index was (re)started within the rule's threshold. */
    static boolean epochTooYoung(Instant epoch, Instant idleBefore) {
        return epoch.isAfter(idleBefore);
    }

    /** More than max(5, 10%) of the city's available drivers at once means the platform lost them, not that they left. */
    static boolean massSilence(int idle, int available) {
        return idle > Math.max(VALVE_MIN_DRIVERS, available * VALVE_PERCENT / 100);
    }
}

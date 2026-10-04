package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.AvailabilityStatus;
import com.ridehailing.dispatch.db.AvailabilityRepository;
import com.ridehailing.dispatch.db.AvailabilityRepository.AvailabilityRow;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LocationProperties;
import com.ridehailing.platform.Transactions;
import com.ridehailing.ride.RideAssignment;
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
 * Silent drivers leave matching after 30 s; an assigned driver silent for 2 min loses the ride (T7), and an available
 * one goes offline after 10 min (LLD §8.9), unless the safety valve says the silence is the platform's fault.
 */
@Service
public class Sweeper {

    static final String SYSTEM_ACTOR = "sweeper";
    static final String IDLE_RULE = "idle";
    static final String UNREACHABLE_RULE = "unreachable";
    private static final int VALVE_MIN_DRIVERS = 5;
    private static final int VALVE_PERCENT = 10;
    private static final Logger log = LoggerFactory.getLogger(Sweeper.class);

    private final AvailabilityRepository availability;
    private final Availability service;
    private final RideAssignment rides;
    private final LiveIndex index;
    private final LocationProperties location;
    private final Transactions transactions;
    private final Clock clock;
    private final Counter removed;
    private final Map<String, Counter> valves;

    Sweeper(AvailabilityRepository availability, Availability service, RideAssignment rides, LiveIndex index,
            LocationProperties location, Transactions transactions, Clock clock, MeterRegistry meters) {
        this.availability = availability;
        this.service = service;
        this.rides = rides;
        this.index = index;
        this.location = location;
        this.transactions = transactions;
        this.clock = clock;
        this.removed = meters.counter("location.sweeper.removed");
        // Registered at zero, so an alert on their increase sees the first trip.
        this.valves = Map.of(IDLE_RULE, meters.counter("sweeper.safety.valve", "rule", IDLE_RULE),
                UNREACHABLE_RULE, meters.counter("sweeper.safety.valve", "rule", UNREACHABLE_RULE));
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

    /** Returns the drivers taken offline, unreachable ones first. */
    List<UUID> sweep(String cityId, Instant now) {
        removed.increment(index.sweep(cityId, now.minus(location.freshness())).size());
        List<UUID> offline = new ArrayList<>(unassignUnreachable(cityId, now));
        offline.addAll(takeIdleOffline(cityId, now));
        return offline;
    }

    /** T7 (LLD §7.9): each assigned driver silent for 2 min loses their ride, in a transaction of its own. */
    private List<UUID> unassignUnreachable(String cityId, Instant now) {
        List<AvailabilityRow> assigned = availability.online(cityId).stream()
                .filter(row -> row.status() == AvailabilityStatus.ASSIGNED)
                .toList();
        Instant silentBefore = now.minus(location.unreachableAfter());
        List<AvailabilityRow> silent = silent(cityId, assigned, silentBefore);
        if (silent.isEmpty() || valveHolds(UNREACHABLE_RULE, cityId, silent.size(), assigned.size(),
                index.epoch(cityId), silentBefore)) {
            return List.of();
        }
        List<UUID> unassigned = new ArrayList<>();
        for (AvailabilityRow driver : silent) {
            try {
                if (stillSilent(cityId, driver, silentBefore) && transactions.execute(
                        () -> rides.unassignUnreachable(driver.rideId(), driver.driverId()))) {
                    unassigned.add(driver.driverId());
                }
            } catch (RuntimeException e) {
                log.error("Unassigning unreachable driver {} failed; the next run tries again", driver.driverId(), e);
            }
        }
        return unassigned;
    }

    private List<UUID> takeIdleOffline(String cityId, Instant now) {
        List<AvailabilityRow> available = availability.online(cityId).stream()
                .filter(row -> row.status() == AvailabilityStatus.AVAILABLE)
                .toList();
        Instant idleBefore = now.minus(location.offlineAfter());
        List<AvailabilityRow> idle = silent(cityId, available, idleBefore);
        Instant epoch = index.epoch(cityId);
        if (idle.isEmpty() || valveHolds(IDLE_RULE, cityId, idle.size(), available.size(), epoch, idleBefore)) {
            return List.of();
        }
        List<UUID> offline = new ArrayList<>();
        for (AvailabilityRow driver : idle) {
            try {
                if (takeOfflineIfIdle(cityId, driver.driverId(), idleBefore, epoch)) {
                    offline.add(driver.driverId());
                }
            } catch (RuntimeException e) {
                log.error("Taking silent driver {} offline failed; the next run tries again", driver.driverId(), e);
            }
        }
        return offline;
    }

    /** The drivers, among {@code rows}, not heard from since {@code before}. */
    private List<AvailabilityRow> silent(String cityId, List<AvailabilityRow> rows, Instant before) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Instant epoch = index.epoch(cityId);
        Map<UUID, Instant> seen = index.lastSeen(cityId, rows.stream().map(AvailabilityRow::driverId).toList());
        return rows.stream()
                .filter(row -> lastSeen(seen.get(row.driverId()), row.onlineSince(), epoch).isBefore(before))
                .toList();
    }

    /** Asked again just before acting: the driver may have spoken since the city was read. */
    private boolean stillSilent(String cityId, AvailabilityRow driver, Instant before) {
        Instant seen = index.lastSeen(cityId, List.of(driver.driverId())).get(driver.driverId());
        return lastSeen(seen, driver.onlineSince(), index.epoch(cityId)).isBefore(before);
    }

    private boolean valveHolds(String rule, String cityId, int silent, int inState, Instant epoch, Instant before) {
        String why;
        if (epochTooYoung(epoch, before)) {
            why = "the index epoch " + epoch + " is younger than the rule's threshold";
        } else if (massSilence(silent, inState)) {
            why = silent + " of " + inState + " drivers are silent";
        } else {
            return false;
        }
        valves.get(rule).increment();
        log.warn("Safety valve ({} rule): not acting on {} silent drivers in city {}, because {}", rule, silent,
                cityId, why);
        return true;
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

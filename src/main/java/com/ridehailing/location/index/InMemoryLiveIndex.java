package com.ridehailing.location.index;

import com.ridehailing.location.LiveIndex;
import com.ridehailing.shared.BoundingBox;
import com.ridehailing.shared.GeoPoint;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The V1 live index (LLD §9.2): per city, one immutable entry per driver, replaced atomically inside
 * {@code compute}, with the rules of the Valkey scripts (§9.4). Correct only inside one process (§1.3).
 */
public class InMemoryLiveIndex implements LiveIndex {

    /** A sweep looks back this far before the cutoff; earlier silences were swept already. */
    static final Duration SWEEP_WINDOW = Duration.ofMinutes(1);

    private final Map<String, Map<UUID, Entry>> cities = new ConcurrentHashMap<>();
    private final Set<String> begun = ConcurrentHashMap.newKeySet();
    private final Clock clock;
    private final Duration freshness;
    private final Duration tombstoneTtl;
    private final Quality quality;
    private final Instant epoch;

    public InMemoryLiveIndex(Clock clock, Duration freshness, Duration tombstoneTtl, Quality quality) {
        this.clock = clock;
        this.freshness = freshness;
        this.tombstoneTtl = tombstoneTtl;
        this.quality = quality;
        this.epoch = clock.instant();
    }

    @Override
    public UpdateResult update(String cityId, UUID driverId, String category, LocationUpdate update) {
        Instant now = clock.instant();
        UpdateResult[] result = new UpdateResult[1];
        city(cityId).compute(driverId, (id, current) -> {
            Entry entry = live(current, now);
            if (entry == null || entry.status() == Status.OFFLINE) {
                result[0] = new UpdateResult(UpdateResult.Outcome.OFFLINE, Status.OFFLINE, null, null);
            } else if (!entry.category().equals(category)) {
                result[0] = new UpdateResult(UpdateResult.Outcome.CATEGORY_MISMATCH, entry.status(), entry.category(),
                        entry.rideId());
            } else if (entry.seq() != null && update.seq() <= entry.seq()) {
                result[0] = new UpdateResult(UpdateResult.Outcome.STALE, entry.status(), null, entry.rideId());
            } else {
                int flags = flags(entry, update, now);
                result[0] = new UpdateResult(UpdateResult.Outcome.APPLIED, entry.status(), null, entry.rideId(), flags);
                if (flags != 0) {
                    // A sign of life only: the position, and with it matching, stays where it was.
                    return new Entry(entry.status(), entry.version(), entry.category(), entry.rideId(), update.seq(),
                            now, entry.position(), entry.positionAt(),
                            flags == UpdateResult.IMPLAUSIBLE ? entry.implausible() + 1 : entry.implausible(),
                            entry.matchable(), null);
                }
                return new Entry(entry.status(), entry.version(), entry.category(), entry.rideId(), update.seq(), now,
                        update.position(), now, 0, entry.status() == Status.AVAILABLE, null);
            }
            return entry;
        });
        return result[0];
    }

    /** §9.5: speed is measured from the last usable position, over a second at least. */
    private int flags(Entry entry, LocationUpdate update, Instant now) {
        if (update.accuracyM() > quality.maxAccuracyM()) {
            return UpdateResult.POOR_ACCURACY;
        }
        if (entry.position() == null) {
            return 0;
        }
        double seconds = Math.max(Duration.between(entry.positionAt(), now).toMillis() / 1000.0, 1);
        boolean tooFast = entry.position().metresTo(update.position()) / seconds > quality.maxSpeedMps();
        return tooFast && entry.implausible() + 1 < quality.reanchorAfter() ? UpdateResult.IMPLAUSIBLE : 0;
    }

    @Override
    public List<Candidate> nearby(String cityId, String category, GeoPoint at, int radiusM, int k) {
        Instant oldest = clock.instant().minus(freshness);
        record Found(UUID driverId, Entry entry, double metres) {
        }
        return city(cityId).entrySet().stream()
                .filter(driver -> driver.getValue().matchableIn(category, oldest))
                .map(driver -> new Found(driver.getKey(), driver.getValue(), at.metresTo(driver.getValue().position())))
                .filter(found -> found.metres() <= radiusM)
                .sorted(Comparator.comparingDouble(Found::metres).thenComparing(Found::driverId))
                .limit(k)
                .map(found -> new Candidate(found.driverId(), found.entry().position(),
                        (int) Math.round(found.metres()), found.entry().seenAt()))
                .toList();
    }

    @Override
    public Optional<LivePosition> position(String cityId, UUID driverId) {
        Entry entry = live(city(cityId).get(driverId), clock.instant());
        if (entry == null || entry.position() == null) {
            return Optional.empty();
        }
        return Optional.of(new LivePosition(entry.position(), entry.positionAt(), entry.seq()));
    }

    @Override
    public boolean mirror(String cityId, UUID driverId, MirrorState state) {
        Instant now = clock.instant();
        boolean[] applied = {false};
        city(cityId).compute(driverId, (id, current) -> {
            Entry entry = live(current, now);
            if (entry != null && entry.version() >= state.version()) {
                return entry;
            }
            applied[0] = true;
            if (state.status() == Status.OFFLINE) {
                // The tombstone keeps only the version, so a restarted app's sequence numbers count again.
                return new Entry(Status.OFFLINE, state.version(), null, null, null, null, null, null, 0, false,
                        now.plus(tombstoneTtl));
            }
            // A tombstone has no position or sequence to keep.
            Entry kept = entry != null ? entry : Entry.EMPTY;
            return new Entry(state.status(), state.version(), state.category(), state.rideId(), kept.seq(),
                    kept.seenAt(), kept.position(), kept.positionAt(), kept.implausible(),
                    state.status() == Status.AVAILABLE && kept.position() != null, null);
        });
        return applied[0];
    }

    @Override
    public List<UUID> sweep(String cityId, Instant silentBefore) {
        Instant now = clock.instant();
        Instant windowStart = silentBefore.minus(SWEEP_WINDOW);
        List<UUID> swept = new ArrayList<>();
        Map<UUID, Entry> drivers = city(cityId);
        for (UUID driverId : drivers.keySet()) {
            drivers.computeIfPresent(driverId, (id, current) -> {
                Entry entry = live(current, now);
                if (entry == null || entry.seenAt() == null || entry.seenAt().isBefore(windowStart)
                        || !entry.seenAt().isBefore(silentBefore)) {
                    return entry;
                }
                swept.add(id);
                return new Entry(entry.status(), entry.version(), entry.category(), entry.rideId(), entry.seq(),
                        entry.seenAt(), entry.position(), entry.positionAt(), entry.implausible(), false,
                        entry.expiresAt());
            });
        }
        return swept;
    }

    @Override
    public Map<UUID, Instant> lastSeen(String cityId, Collection<UUID> driverIds) {
        Instant now = clock.instant();
        Map<UUID, Entry> drivers = city(cityId);
        Map<UUID, Instant> seen = new HashMap<>();
        for (UUID driverId : driverIds) {
            Entry entry = live(drivers.get(driverId), now);
            if (entry != null && entry.seenAt() != null) {
                seen.put(driverId, entry.seenAt());
            }
        }
        return seen;
    }

    /** The index's creation, whether or not the city has begun: the process's memory began then. */
    @Override
    public Instant epoch(String cityId) {
        return epoch;
    }

    @Override
    public boolean beginEpoch(String cityId) {
        return begun.add(cityId);
    }

    @Override
    public Map<UUID, MirrorState> mirrored(String cityId) {
        Instant now = clock.instant();
        Map<UUID, MirrorState> states = new HashMap<>();
        city(cityId).forEach((driverId, current) -> {
            Entry entry = live(current, now);
            if (entry != null) {
                states.put(driverId, new MirrorState(entry.status(), entry.version(), entry.category(),
                        entry.rideId()));
            }
        });
        return states;
    }

    @Override
    public List<OnlineDriver> snapshot(String cityId, BoundingBox box, int max) {
        Instant now = clock.instant();
        GeoPoint centre = box.centre();
        record Found(UUID driverId, Entry entry, double metres) {
        }
        List<Found> found = new ArrayList<>();
        city(cityId).forEach((driverId, current) -> {
            Entry entry = live(current, now);
            // Tombstones have no position.
            if (entry != null && entry.position() != null && box.contains(entry.position())) {
                found.add(new Found(driverId, entry, centre.metresTo(entry.position())));
            }
        });
        return found.stream()
                .sorted(Comparator.comparingDouble(Found::metres).thenComparing(Found::driverId))
                .limit(max)
                .map(driver -> new OnlineDriver(driver.driverId(), driver.entry().position(), driver.entry().status(),
                        driver.entry().category(), driver.entry().rideId(), driver.entry().seenAt()))
                .toList();
    }

    private Map<UUID, Entry> city(String cityId) {
        return cities.computeIfAbsent(cityId, id -> new ConcurrentHashMap<>());
    }

    /** An expired tombstone counts as no entry. */
    private static Entry live(Entry entry, Instant now) {
        return entry != null && entry.expiresAt() != null && !now.isBefore(entry.expiresAt()) ? null : entry;
    }

    /**
     * {@code seq}, {@code seenAt}, {@code position} and {@code positionAt} are null until the first update after going
     * online; {@code seenAt} is the last applied update, {@code position} the last usable one, and
     * {@code implausible} counts implausible updates since; {@code matchable} is membership of the category's GEO
     * set, so only {@code AVAILABLE} entries have it; {@code expiresAt} is set on tombstones only.
     */
    private record Entry(Status status, long version, String category, UUID rideId, Long seq, Instant seenAt,
            GeoPoint position, Instant positionAt, int implausible, boolean matchable, Instant expiresAt) {

        static final Entry EMPTY = new Entry(Status.OFFLINE, 0, null, null, null, null, null, null, 0, false, null);

        boolean matchableIn(String wanted, Instant oldest) {
            return matchable && wanted.equals(category) && !seenAt.isBefore(oldest);
        }
    }
}

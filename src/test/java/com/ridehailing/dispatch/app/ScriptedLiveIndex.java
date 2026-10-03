package com.ridehailing.dispatch.app;

import com.ridehailing.location.LiveIndex;
import com.ridehailing.shared.GeoPoint;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The real index, with answers a test may override: last-seen times and the epoch, as after a Valkey epoch reset
 * that kept older data; a hook on the first last-seen read; and cities whose sweep fails.
 */
class ScriptedLiveIndex implements LiveIndex {

    final Map<UUID, Instant> seen = new ConcurrentHashMap<>();
    final Map<String, Instant> epochs = new ConcurrentHashMap<>();
    final Set<String> failingSweeps = ConcurrentHashMap.newKeySet();
    final List<String> sweptCities = new CopyOnWriteArrayList<>();
    /** Answers last-seen reads after the first, in place of {@link #seen}, when set. */
    volatile Function<UUID, Instant> laterSeen;
    volatile Runnable onFirstLastSeen;

    private final LiveIndex real;
    private volatile boolean lastSeenRead;

    ScriptedLiveIndex(LiveIndex real) {
        this.real = real;
    }

    @Override
    public UpdateResult update(String cityId, UUID driverId, String category, LocationUpdate update) {
        return real.update(cityId, driverId, category, update);
    }

    @Override
    public List<Candidate> nearby(String cityId, String category, GeoPoint at, int radiusM, int k) {
        return real.nearby(cityId, category, at, radiusM, k);
    }

    @Override
    public Optional<LivePosition> position(String cityId, UUID driverId) {
        return real.position(cityId, driverId);
    }

    @Override
    public boolean mirror(String cityId, UUID driverId, MirrorState state) {
        return real.mirror(cityId, driverId, state);
    }

    @Override
    public List<UUID> sweep(String cityId, Instant silentBefore) {
        sweptCities.add(cityId);
        if (failingSweeps.contains(cityId) || failingSweeps.contains("*")) {
            throw new IllegalStateException("sweeping " + cityId + " failed");
        }
        return real.sweep(cityId, silentBefore);
    }

    @Override
    public Map<UUID, Instant> lastSeen(String cityId, Collection<UUID> drivers) {
        boolean first = !lastSeenRead;
        lastSeenRead = true;
        if (first && onFirstLastSeen != null) {
            onFirstLastSeen.run();
        }
        Map<UUID, Instant> answer = new HashMap<>(real.lastSeen(cityId, drivers));
        for (UUID driver : drivers) {
            Instant scripted = !first && laterSeen != null ? laterSeen.apply(driver) : seen.get(driver);
            if (scripted != null) {
                answer.put(driver, scripted);
            }
        }
        return answer;
    }

    @Override
    public Instant epoch(String cityId) {
        return epochs.getOrDefault(cityId, real.epoch(cityId));
    }

    @Override
    public Map<UUID, MirrorState> mirrored(String cityId) {
        return real.mirrored(cityId);
    }
}

package com.ridehailing.location.index;

import com.ridehailing.location.LiveIndex;
import com.ridehailing.platform.Valkey;
import com.ridehailing.platform.Valkey.Script;
import com.ridehailing.shared.BoundingBox;
import com.ridehailing.shared.GeoPoint;
import io.lettuce.core.GeoArgs;
import io.lettuce.core.GeoSearch;
import io.lettuce.core.GeoWithin;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
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
import java.util.stream.Stream;

/**
 * The V2 live index (LLD §9.3, §9.4): one Lua script per update, query, mirror write and sweep, every key of a call
 * under the city's hash tag. Times are the application clock's milliseconds, passed to the scripts.
 */
public class ValkeyLiveIndex implements LiveIndex {

    static final Script UPDATE = Script.named("live_update");
    static final Script QUERY = Script.named("live_query");
    static final Script MIRROR = Script.named("live_mirror");
    static final Script SWEEP = Script.named("live_sweep");
    static final Script STATES = Script.named("live_states");

    /** Valkey measures with a larger Earth radius and keeps geohashes: search a little wider, then measure. */
    private static final double RADIUS_SLACK = 1.001;
    private static final double BOX_SLACK = 1.01;
    private static final int STATES_CHUNK = 500;
    private static final int MIRROR_TRIES = 10;
    private static final int FIELDS_PER_STATE = 8;

    private final Valkey valkey;
    private final Clock clock;
    private final Duration freshness;
    private final Duration tombstoneTtl;

    public ValkeyLiveIndex(Valkey valkey, Clock clock, Duration freshness, Duration tombstoneTtl) {
        this.valkey = valkey;
        this.clock = clock;
        this.freshness = freshness;
        this.tombstoneTtl = tombstoneTtl;
        valkey.load(UPDATE, QUERY, MIRROR, SWEEP, STATES);
    }

    @Override
    public UpdateResult update(String cityId, UUID driverId, String category, LocationUpdate update) {
        List<Object> reply = valkey.run(UPDATE, ScriptOutputType.MULTI, valkey.timeouts().update(),
                keys(driver(cityId, driverId), seen(cityId), geo(cityId, category), geo(cityId, "online")),
                driverId.toString(), Long.toString(update.seq()), Long.toString(clock.millis()),
                Double.toString(update.position().lat()), Double.toString(update.position().lon()),
                Double.toString(update.accuracyM()), text(update.headingDeg()), text(update.speedMps()), category);
        long code = (Long) reply.get(0);
        Status status = Status.valueOf((String) reply.get(1));
        UUID ride = uuid((String) reply.get(3));
        if (code == 1) {
            return new UpdateResult(UpdateResult.Outcome.APPLIED, status, null, ride);
        } else if (code == 0) {
            return new UpdateResult(UpdateResult.Outcome.STALE, status, null, ride);
        } else if (code == -1) {
            return new UpdateResult(UpdateResult.Outcome.OFFLINE, Status.OFFLINE, null, null);
        } else if (code == -2) {
            return new UpdateResult(UpdateResult.Outcome.CATEGORY_MISMATCH, status, (String) reply.get(2), ride);
        }
        throw new IllegalStateException(UPDATE.name() + " answered " + code);
    }

    @Override
    public List<Candidate> nearby(String cityId, String category, GeoPoint at, int radiusM, int k) {
        if (k <= 0) {
            return List.of();
        }
        Duration timeout = valkey.timeouts().query();
        List<String> found = valkey.run(QUERY, ScriptOutputType.MULTI, timeout, keys(geo(cityId, category),
                seen(cityId)), Double.toString(at.lon()), Double.toString(at.lat()),
                Double.toString(radiusM * RADIUS_SLACK + 1), Integer.toString(2 * k),
                Long.toString(clock.millis() - freshness.toMillis()));
        List<RedisFuture<List<KeyValue<String, String>>>> reads = new ArrayList<>();
        for (int i = 0; i < found.size(); i += 2) {
            reads.add(valkey.commands().hmget(driver(cityId, UUID.fromString(found.get(i))), "plat", "plon"));
        }
        List<List<KeyValue<String, String>>> positions = valkey.await(QUERY.name(), timeout, reads);
        record Found(UUID driverId, GeoPoint position, double metres, Instant lastSeen) {
        }
        List<Found> candidates = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            // A driver who went offline since the search has no position.
            Optional<GeoPoint> position = point(value(positions.get(i), 0), value(positions.get(i), 1));
            if (position.isPresent() && at.metresTo(position.get()) <= radiusM) {
                candidates.add(new Found(UUID.fromString(found.get(2 * i)), position.get(),
                        at.metresTo(position.get()), instant(found.get(2 * i + 1))));
            }
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble(Found::metres).thenComparing(Found::driverId))
                .limit(k)
                .map(candidate -> new Candidate(candidate.driverId(), candidate.position(),
                        (int) Math.round(candidate.metres()), candidate.lastSeen()))
                .toList();
    }

    @Override
    public Optional<LivePosition> position(String cityId, UUID driverId) {
        List<KeyValue<String, String>> fields = valkey.await("live_position", valkey.timeouts().other(),
                valkey.commands().hmget(driver(cityId, driverId), "plat", "plon", "pts", "seq"));
        return point(value(fields, 0), value(fields, 1)).map(position -> new LivePosition(position,
                instant(value(fields, 2)), Long.parseLong(value(fields, 3))));
    }

    @Override
    public boolean mirror(String cityId, UUID driverId, MirrorState state) {
        Duration timeout = valkey.timeouts().mirror();
        String hash = driver(cityId, driverId);
        String category = state.category() == null ? "" : state.category();
        for (int attempt = 1; ; attempt++) {
            String read = valkey.await(MIRROR.name(), timeout, valkey.commands().hget(hash, "cat"));
            String held = read == null ? "" : read;
            long now = clock.millis();
            long answer = valkey.<Long>run(MIRROR, ScriptOutputType.INTEGER, timeout,
                    keys(hash, seen(cityId), geo(cityId, "online"), drivers(cityId), categories(cityId),
                            geo(cityId, held), geo(cityId, category)),
                    driverId.toString(), state.status().name(), Long.toString(state.version()), category,
                    state.rideId() == null ? "" : state.rideId().toString(), Long.toString(now),
                    Long.toString(now + tombstoneTtl.toMillis()), Long.toString(tombstoneTtl.toMillis()), held);
            if (answer != -1) {
                return answer == 1;
            }
            if (attempt == MIRROR_TRIES) {
                throw new IllegalStateException("Driver " + driverId + "'s category kept changing during a mirror write");
            }
        }
    }

    @Override
    public List<UUID> sweep(String cityId, Instant silentBefore) {
        Duration timeout = valkey.timeouts().other();
        Set<String> categories = valkey.await(SWEEP.name(), timeout, valkey.commands().smembers(categories(cityId)));
        long cutoff = silentBefore.toEpochMilli();
        List<String> swept = valkey.run(SWEEP, ScriptOutputType.MULTI, timeout,
                Stream.concat(Stream.of(seen(cityId)), categories.stream().sorted().map(each -> geo(cityId, each)))
                        .toArray(String[]::new),
                Long.toString(cutoff), Long.toString(cutoff - InMemoryLiveIndex.SWEEP_WINDOW.toMillis()));
        return swept.stream().map(UUID::fromString).toList();
    }

    @Override
    public Map<UUID, Instant> lastSeen(String cityId, Collection<UUID> drivers) {
        if (drivers.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = List.copyOf(drivers);
        List<Double> scores = valkey.await("live_last_seen", valkey.timeouts().other(), valkey.commands()
                .zmscore(seen(cityId), ids.stream().map(UUID::toString).toArray(String[]::new)));
        Map<UUID, Instant> seen = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            if (scores.get(i) != null) {
                seen.put(ids.get(i), Instant.ofEpochMilli(scores.get(i).longValue()));
            }
        }
        return seen;
    }

    @Override
    public Instant epoch(String cityId) {
        String stored = valkey.await("live_epoch", valkey.timeouts().other(), valkey.commands().get(epochKey(cityId)));
        return stored == null ? clock.instant() : instant(stored);
    }

    @Override
    public boolean beginEpoch(String cityId) {
        return "OK".equals(valkey.await("live_epoch", valkey.timeouts().other(), valkey.commands()
                .set(epochKey(cityId), Long.toString(clock.millis()), SetArgs.Builder.nx())));
    }

    @Override
    public Map<UUID, MirrorState> mirrored(String cityId) {
        List<String> members = List.copyOf(valkey.await("live_mirrored", valkey.timeouts().other(),
                valkey.commands().smembers(drivers(cityId))));
        Map<UUID, MirrorState> states = new HashMap<>();
        for (int from = 0; from < members.size(); from += STATES_CHUNK) {
            for (Held held : states(cityId, members.subList(from, Math.min(members.size(), from + STATES_CHUNK)),
                    true)) {
                states.put(held.driverId(), new MirrorState(held.status(), held.version(), held.category(),
                        held.rideId()));
            }
        }
        return states;
    }

    @Override
    public List<OnlineDriver> snapshot(String cityId, BoundingBox box, int max) {
        if (max <= 0) {
            return List.of();
        }
        GeoPoint centre = box.centre();
        double heightM = new GeoPoint(box.minLat(), centre.lon()).metresTo(new GeoPoint(box.maxLat(), centre.lon()));
        double widthM = Math.max(
                new GeoPoint(box.minLat(), box.minLon()).metresTo(new GeoPoint(box.minLat(), box.maxLon())),
                new GeoPoint(box.maxLat(), box.minLon()).metresTo(new GeoPoint(box.maxLat(), box.maxLon())));
        List<GeoWithin<String>> found = valkey.await("live_snapshot", valkey.timeouts().other(),
                valkey.commands().geosearch(geo(cityId, "online"), GeoSearch.fromCoordinates(centre.lon(),
                        centre.lat()), GeoSearch.byBox(widthM * BOX_SLACK + 1, heightM * BOX_SLACK + 1,
                        GeoArgs.Unit.m), new GeoArgs().withCount(2L * max).asc()));
        if (found.isEmpty()) {
            return List.of();
        }
        return states(cityId, found.stream().map(GeoWithin::getMember).toList(), false).stream()
                // Tombstones have no position.
                .filter(held -> held.position() != null && box.contains(held.position()))
                .sorted(Comparator.<Held>comparingDouble(held -> centre.metresTo(held.position()))
                        .thenComparing(Held::driverId))
                .limit(max)
                .map(held -> new OnlineDriver(held.driverId(), held.position(), held.status(), held.category(),
                        held.rideId(), held.lastSeen()))
                .toList();
    }

    private List<Held> states(String cityId, List<String> ids, boolean forgetGone) {
        String[] keys = new String[ids.size() + 1];
        String[] args = new String[ids.size() + 2];
        keys[0] = drivers(cityId);
        args[0] = Long.toString(clock.millis());
        args[1] = forgetGone ? "1" : "0";
        for (int i = 0; i < ids.size(); i++) {
            keys[i + 1] = driver(cityId, UUID.fromString(ids.get(i)));
            args[i + 2] = ids.get(i);
        }
        List<String> flat = valkey.run(STATES, ScriptOutputType.MULTI, valkey.timeouts().other(), keys, args);
        List<Held> held = new ArrayList<>();
        for (int i = 0; i < flat.size(); i += FIELDS_PER_STATE) {
            held.add(new Held(UUID.fromString(flat.get(i)), Status.valueOf(flat.get(i + 1)),
                    Long.parseLong(flat.get(i + 2)), blankToNull(flat.get(i + 3)), uuid(flat.get(i + 4)),
                    flat.get(i + 5).isEmpty() ? null : instant(flat.get(i + 5)),
                    point(flat.get(i + 6), flat.get(i + 7)).orElse(null)));
        }
        return held;
    }

    private static String[] keys(String... keys) {
        return keys;
    }

    private static String driver(String cityId, UUID driverId) {
        return "{" + cityId + "}:drv:" + driverId;
    }

    private static String seen(String cityId) {
        return "{" + cityId + "}:seen";
    }

    /** The GEO set of a category's available drivers, or {@code online} for every online driver. */
    private static String geo(String cityId, String category) {
        return "{" + cityId + "}:geo:" + category;
    }

    private static String drivers(String cityId) {
        return "{" + cityId + "}:drivers";
    }

    private static String categories(String cityId) {
        return "{" + cityId + "}:cats";
    }

    private static String epochKey(String cityId) {
        return "{" + cityId + "}:epoch";
    }

    private static String text(Double value) {
        return value == null ? "" : value.toString();
    }

    private static String value(List<KeyValue<String, String>> fields, int index) {
        return fields.get(index).getValueOrElse(null);
    }

    private static Optional<GeoPoint> point(String lat, String lon) {
        return lat == null || lat.isEmpty() || lon == null || lon.isEmpty() ? Optional.empty()
                : Optional.of(new GeoPoint(Double.parseDouble(lat), Double.parseDouble(lon)));
    }

    /** Milliseconds as Valkey writes them: a score may come back as a double. */
    private static Instant instant(String millis) {
        return Instant.ofEpochMilli((long) Double.parseDouble(millis));
    }

    private static UUID uuid(String value) {
        return value == null || value.isEmpty() ? null : UUID.fromString(value);
    }

    private static String blankToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private record Held(UUID driverId, Status status, long version, String category, UUID rideId, Instant lastSeen,
            GeoPoint position) {
    }
}

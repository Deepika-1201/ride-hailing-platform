package com.ridehailing.location.app;

import com.ridehailing.geography.GeographyApi;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.LocationUpdate;
import com.ridehailing.location.LiveIndex.UpdateResult;
import com.ridehailing.location.LocationIngestion;
import com.ridehailing.location.trips.TripPoint;
import com.ridehailing.location.trips.TripPointBuffer;
import com.ridehailing.shared.BoundingBox;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * Applies a driver's batch in sequence order (LLD §9.6) and buffers its trip points (§9.8). An update outside the
 * city's bounds is ignored, not rejected: the app sends what the device reports, and one bad fix mustn't fail the
 * batch.
 */
@Service
class Ingestion implements LocationIngestion {

    private final LiveIndex index;
    private final GeographyApi geography;
    private final TripPointBuffer tripPoints;
    private final Clock clock;
    private final MeterRegistry meters;
    /** City bounds don't change once the city exists. */
    private final Map<String, BoundingBox> bounds = new ConcurrentHashMap<>();

    Ingestion(LiveIndex index, GeographyApi geography, TripPointBuffer tripPoints, Clock clock, MeterRegistry meters) {
        this.index = index;
        this.geography = geography;
        this.tripPoints = tripPoints;
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    public BatchResult accept(String cityId, String category, UUID driverId, List<Incoming> updates,
            Function<Instant, Optional<UUID>> replayedRide) {
        Instant receivedAt = clock.instant();
        int applied = 0;
        int stale = 0;
        int ignored = 0;
        Long lastAppliedSeq = null;
        for (Incoming incoming : updates.stream().sorted(Comparator.comparingLong(next -> next.update().seq()))
                .toList()) {
            UpdateResult result = apply(cityId, category, driverId, incoming, receivedAt, replayedRide).orElse(null);
            if (result == null) {
                ignored++;
                continue;
            }
            switch (result.outcome()) {
                case APPLIED -> {
                    applied++;
                    lastAppliedSeq = incoming.update().seq();
                }
                case STALE -> stale++;
                case OFFLINE, CATEGORY_MISMATCH -> ignored++;
            }
        }
        return new BatchResult(applied, stale, ignored, lastAppliedSeq);
    }

    @Override
    public Optional<UpdateResult> live(String cityId, String category, UUID driverId, LocationUpdate update) {
        return apply(cityId, category, driverId, new Incoming(update, false), clock.instant(),
                deviceTime -> Optional.empty());
    }

    /** Empty for an invalid or out-of-bounds update, which is counted and ignored. */
    private Optional<UpdateResult> apply(String cityId, String category, UUID driverId, Incoming incoming,
            Instant receivedAt, Function<Instant, Optional<UUID>> replayedRide) {
        LocationUpdate update = incoming.update();
        Optional<BoundingBox> box = bounds(cityId);
        if (box.isEmpty() || !plausible(update) || !box.get().contains(update.position())) {
            count("invalid");
            return Optional.empty();
        }
        UpdateResult result = index.update(cityId, driverId, category, update);
        if (result.outcome() == UpdateResult.Outcome.CATEGORY_MISMATCH) {
            // The vehicle's category is the mirror's; the caller's copy was read before a change.
            result = index.update(cityId, driverId, result.category(), update);
        }
        if (result.outcome() == UpdateResult.Outcome.APPLIED) {
            if (result.flags() == UpdateResult.POOR_ACCURACY) {
                meters.counter("location.update.flags", "flag", "poor_accuracy").increment();
            } else if (result.flags() == UpdateResult.IMPLAUSIBLE) {
                meters.counter("location.update.flags", "flag", "implausible").increment();
            }
        }
        count(result.outcome().name().toLowerCase(Locale.ROOT));
        buffer(driverId, incoming, result, receivedAt, replayedRide);
        return Optional.of(result);
    }

    /**
     * A live update is a point of the ride the index has the driver on, if it applied; a replayed one is a point of the
     * ride the driver had at its device time, whatever the index answered. Only an applied one has quality flags.
     */
    private void buffer(UUID driverId, Incoming incoming, UpdateResult result, Instant receivedAt,
            Function<Instant, Optional<UUID>> replayedRide) {
        boolean isApplied = result.outcome() == UpdateResult.Outcome.APPLIED;
        Optional<UUID> rideId = incoming.replay() ? replayedRide.apply(incoming.update().deviceTime())
                : isApplied ? Optional.ofNullable(result.rideId()) : Optional.empty();
        if (rideId.isPresent()) {
            int flags = (isApplied ? result.flags() : 0) | (incoming.replay() ? TripPoint.REPLAYED : 0);
            tripPoints.add(TripPoint.of(rideId.get(), driverId, receivedAt, incoming.update(), flags));
        }
    }

    private Optional<BoundingBox> bounds(String cityId) {
        BoundingBox known = bounds.get(cityId);
        if (known != null) {
            return Optional.of(known);
        }
        Optional<BoundingBox> found = geography.bounds(cityId);
        found.ifPresent(box -> bounds.put(cityId, box));
        return found;
    }

    private static boolean plausible(LocationUpdate update) {
        return Double.isFinite(update.position().lat()) && Double.isFinite(update.position().lon())
                && Double.isFinite(update.accuracyM()) && update.accuracyM() >= 0;
    }

    private void count(String result) {
        meters.counter("location.updates", "result", result).increment();
    }
}

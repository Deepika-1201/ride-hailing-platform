package com.ridehailing.location.app;

import com.ridehailing.geography.GeographyApi;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.LocationUpdate;
import com.ridehailing.location.LiveIndex.UpdateResult;
import com.ridehailing.location.LocationIngestion;
import com.ridehailing.shared.BoundingBox;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Applies a driver's batch in sequence order (LLD §9.6). An update outside the city's bounds is ignored, not
 * rejected: the app sends what the device reports, and one bad fix mustn't fail the batch.
 */
@Service
class Ingestion implements LocationIngestion {

    private final LiveIndex index;
    private final GeographyApi geography;
    private final MeterRegistry meters;
    /** City bounds don't change once the city exists. */
    private final Map<String, BoundingBox> bounds = new ConcurrentHashMap<>();

    Ingestion(LiveIndex index, GeographyApi geography, MeterRegistry meters) {
        this.index = index;
        this.geography = geography;
        this.meters = meters;
    }

    @Override
    public BatchResult accept(String cityId, String category, UUID driverId, List<LocationUpdate> updates) {
        Optional<BoundingBox> box = bounds(cityId);
        String expected = category;
        int applied = 0;
        int stale = 0;
        int ignored = 0;
        Long lastAppliedSeq = null;
        for (LocationUpdate update : updates.stream().sorted(Comparator.comparingLong(LocationUpdate::seq)).toList()) {
            if (box.isEmpty() || !plausible(update) || !box.get().contains(update.position())) {
                ignored++;
                count("invalid");
                continue;
            }
            UpdateResult result = index.update(cityId, driverId, expected, update);
            if (result.outcome() == UpdateResult.Outcome.CATEGORY_MISMATCH) {
                // The vehicle's category is the mirror's; the caller's copy was read before a change.
                expected = result.category();
                result = index.update(cityId, driverId, expected, update);
            }
            switch (result.outcome()) {
                case APPLIED -> {
                    applied++;
                    lastAppliedSeq = update.seq();
                }
                case STALE -> stale++;
                case OFFLINE, CATEGORY_MISMATCH -> ignored++;
            }
            count(result.outcome().name().toLowerCase(Locale.ROOT));
        }
        return new BatchResult(applied, stale, ignored, lastAppliedSeq);
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

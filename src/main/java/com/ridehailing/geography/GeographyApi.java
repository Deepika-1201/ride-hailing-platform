package com.ridehailing.geography;

import com.ridehailing.shared.BoundingBox;
import com.ridehailing.shared.GeoPoint;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Cities, service areas and pricing zones, for the other modules (LLD §2.2, §10.1). */
public interface GeographyApi {

    Optional<CityView> city(String cityId);

    /** Whether the city is active and offers the category. */
    boolean offers(String cityId, String category);

    /** The city's dispatch settings for the category, offered or not. */
    Optional<CategorySettings> settings(String cityId, String category);

    /** The service area containing the point, with its zone; empty outside every service area. */
    Optional<Location> locate(GeoPoint point);

    /** The envelope of the city's bounds, for validating locations; empty for an unknown city. */
    Optional<BoundingBox> bounds(String cityId);

    /** An H3 resolution-7 cell, or {@code area:<code>} of an active special area in the city. */
    boolean isZone(String cityId, String zoneId);

    /** Counts up to 10000 points inside this city's service area; special areas have no implicit neighbor ring. */
    Map<String, ZoneCount> countByZone(String cityId, List<GeoPoint> points);

    record ZoneCount(long local, long neighboring) {
    }

    record CityView(String id, String name, ZoneId timeZone, String currency, boolean active) {
    }

    record CategorySettings(boolean active, int offerTtlS, int searchTimeoutS, int radiusStartM, int radiusStepM,
            int radiusMaxM, String ranker, RankingWeights rankingWeights, RankingExperiment rankingExperiment, int version) {

        public CategorySettings(boolean active, int offerTtlS, int searchTimeoutS, int radiusStartM, int radiusStepM,
                int radiusMaxM, String ranker, RankingWeights rankingWeights) {
            this(active, offerTtlS, searchTimeoutS, radiusStartM, radiusStepM, radiusMaxM, ranker, rankingWeights, null, 0);
        }

        public CategorySettings(boolean active, int offerTtlS, int searchTimeoutS, int radiusStartM, int radiusStepM,
                int radiusMaxM, String ranker) {
            this(active, offerTtlS, searchTimeoutS, radiusStartM, radiusStepM, radiusMaxM, ranker, RankingWeights.DEFAULT);
        }
    }

    /** {@code zoneId} is {@code area:<code>} inside a special area, otherwise the point's H3 resolution-7 cell. */
    record Location(String cityId, String zoneId) {
    }
}

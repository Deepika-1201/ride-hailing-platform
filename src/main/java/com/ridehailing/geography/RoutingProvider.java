package com.ridehailing.geography;

import com.ridehailing.shared.GeoPoint;
import java.time.ZonedDateTime;
import java.util.Optional;

/** Road distance and travel time between two points (ADR-013): the mock in V1–V3, OSRM from V4. */
public interface RoutingProvider {

    /** Empty when the points can't be connected by road. */
    Optional<Route> route(GeoPoint from, GeoPoint to, ZonedDateTime departure);

    record Route(int distanceM, int durationS, Source source) {

        /** Recorded with each quote, so a fallback to the mock shows in the data (LLD §10.4). */
        public enum Source {
            MOCK,
            OSRM,
            MOCK_FALLBACK
        }
    }
}

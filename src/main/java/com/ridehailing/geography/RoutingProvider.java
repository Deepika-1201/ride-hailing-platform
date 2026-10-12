package com.ridehailing.geography;

import com.ridehailing.shared.GeoPoint;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/** Road distance and travel time between two points (ADR-013): the mock in V1–V3, OSRM from V4. */
public interface RoutingProvider {

    /** Empty when the points can't be connected by road. */
    Optional<Route> route(GeoPoint from, GeoPoint to, ZonedDateTime departure);

    default Optional<DurationMatrix> matrix(String cityId, List<GeoPoint> origins, List<GeoPoint> destinations,
            ZonedDateTime departure) {
        return Optional.empty();
    }

    record DurationMatrix(List<List<OptionalDouble>> durationsS) {

        public DurationMatrix {
            durationsS = durationsS.stream().map(List::copyOf).toList();
            int columns = durationsS.isEmpty() ? 0 : durationsS.getFirst().size();
            if (columns == 0 || durationsS.stream().anyMatch(row -> row.size() != columns
                        || row.stream().anyMatch(value -> value.isPresent()
                            && (!Double.isFinite(value.getAsDouble()) || value.getAsDouble() < 0)))) {
                throw new IllegalArgumentException("A duration matrix must be rectangular with nonnegative finite durations or unreachable cells");
            }
        }
    }

    record Route(int distanceM, int durationS, Source source) {

        /** Recorded with each quote, so a fallback to the mock shows in the data (LLD §10.4). */
        public enum Source {
            MOCK,
            OSRM,
            MOCK_FALLBACK
        }
    }
}

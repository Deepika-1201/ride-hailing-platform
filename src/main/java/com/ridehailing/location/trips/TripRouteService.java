package com.ridehailing.location.trips;

import com.ridehailing.location.TripRoutes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class TripRouteService implements TripRoutes {

    private final TripPointRepository repository;

    TripRouteService(TripPointRepository repository) {
        this.repository = repository;
    }

    @Override
    public TripRoute route(UUID rideId, int maxPoints) {
        if (maxPoints < 2) {
            throw new IllegalArgumentException("A route keeps its first and last points, so maxPoints must be 2 or more");
        }
        List<RoutePoint> points = repository.route(rideId);
        return new TripRoute(rideId, points.size() > maxPoints, thin(points, maxPoints));
    }

    /** {@code max} points at evenly spaced positions, the first and the last among them (LLD §9.8). */
    static <T> List<T> thin(List<T> points, int max) {
        int n = points.size();
        if (n <= max) {
            return points;
        }
        List<T> kept = new ArrayList<>(max);
        for (long i = 0; i < max; i++) {
            kept.add(points.get((int) ((i * (n - 1) + (max - 1) / 2) / (max - 1))));
        }
        return kept;
    }
}

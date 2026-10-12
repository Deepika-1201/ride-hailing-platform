package com.ridehailing.dispatch.app;

import com.ridehailing.geography.RoutingProvider;
import com.ridehailing.geography.RoutingProvider.DurationMatrix;
import com.ridehailing.shared.GeoPoint;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

final class BatchMatrixPreparer {

    private static final int TILE_SIZE = 50;
    private final RoutingProvider routing;
    private final LongSupplier nanoTime;

    BatchMatrixPreparer(RoutingProvider routing) {
        this(routing, System::nanoTime);
    }

    BatchMatrixPreparer(RoutingProvider routing, LongSupplier nanoTime) {
        this.routing = Objects.requireNonNull(routing);
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    Result prepare(String cityId, List<Driver> drivers, List<Request> requests, ZonedDateTime departure, Duration budget) {
        drivers = List.copyOf(drivers);
        requests = List.copyOf(requests);
        if (cityId == null || cityId.isBlank() || departure == null || budget == null || budget.isNegative()
                || budget.isZero() || budget.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Matrix preparation requires a city, departure and budget in (0, 5s]");
        }
        if (drivers.size() > BatchMatcher.MAX_SIZE || requests.size() > BatchMatcher.MAX_SIZE) {
            throw new IllegalArgumentException("Matrix preparation accepts at most 200 drivers and 200 rides");
        }
        Set<UUID> driverIds = new HashSet<>();
        Set<UUID> rideIds = new HashSet<>();
        for (Driver driver : drivers) {
            if (!driverIds.add(driver.id())) {
                throw new IllegalArgumentException("Duplicate driver in batch matrix");
            }
        }
        for (Request request : requests) {
            if (!rideIds.add(request.id()) || !driverIds.containsAll(request.eligibleDrivers())) {
                throw new IllegalArgumentException("Duplicate ride or unknown eligible driver in batch matrix");
            }
        }
        if (drivers.isEmpty() || requests.isEmpty()) {
            return new Result(null, Failure.EMPTY_BATCH, 0);
        }
        long started = nanoTime.getAsLong();
        long budgetNanos = budget.toNanos();
        List<List<OptionalDouble>> durations = new ArrayList<>();
        for (int driver = 0; driver < drivers.size(); driver++) {
            durations.add(new ArrayList<>(Collections.nCopies(requests.size(), OptionalDouble.empty())));
        }
        List<Double> factors = new ArrayList<>(Collections.nCopies(drivers.size(), null));
        int calls = 0;
        for (int firstDriver = 0; firstDriver < drivers.size(); firstDriver += TILE_SIZE) {
            List<Driver> origins = drivers.subList(firstDriver, Math.min(firstDriver + TILE_SIZE, drivers.size()));
            for (int firstRide = 0; firstRide < requests.size(); firstRide += TILE_SIZE) {
                if (Thread.currentThread().isInterrupted()) {
                    return new Result(null, Failure.INTERRUPTED, calls);
                }
                if (nanoTime.getAsLong() - started >= budgetNanos) {
                    return new Result(null, Failure.BUDGET_EXCEEDED, calls);
                }
                List<Request> destinations = requests.subList(firstRide, Math.min(firstRide + TILE_SIZE, requests.size()));
                Optional<DurationMatrix> tile;
                calls++;
                try {
                    tile = routing.matrix(cityId, origins.stream().map(Driver::position).toList(),
                            destinations.stream().map(Request::pickup).toList(), departure);
                } catch (RuntimeException failure) {
                    return new Result(null, Failure.MATRIX_UNAVAILABLE, calls);
                }
                if (Thread.currentThread().isInterrupted()) {
                    return new Result(null, Failure.INTERRUPTED, calls);
                }
                if (nanoTime.getAsLong() - started >= budgetNanos) {
                    return new Result(null, Failure.BUDGET_EXCEEDED, calls);
                }
                if (tile.isEmpty()) {
                    return new Result(null, Failure.MATRIX_UNAVAILABLE, calls);
                }
                DurationMatrix matrix = tile.orElseThrow();
                if (matrix.durationsS().size() != origins.size()
                        || matrix.durationsS().stream().anyMatch(row -> row.size() != destinations.size())) {
                    return new Result(null, Failure.INVALID_MATRIX, calls);
                }
                for (int localDriver = 0; localDriver < origins.size(); localDriver++) {
                    int driver = firstDriver + localDriver;
                    double factor = matrix.trafficFactors().isEmpty() ? 1 : matrix.trafficFactors().get(localDriver);
                    if (factors.get(driver) != null && Double.compare(factors.get(driver), factor) != 0) {
                        return new Result(null, Failure.CHANGED_TRAFFIC_FACTOR, calls);
                    }
                    factors.set(driver, factor);
                    for (int localRide = 0; localRide < destinations.size(); localRide++) {
                        OptionalDouble duration = matrix.durationsS().get(localDriver).get(localRide);
                        if (duration.isPresent() && duration.getAsDouble() > Integer.MAX_VALUE) {
                            return new Result(null, Failure.INVALID_MATRIX, calls);
                        }
                        if (destinations.get(localRide).eligibleDrivers().contains(origins.get(localDriver).id())) {
                            durations.get(driver).set(firstRide + localRide, duration);
                        }
                    }
                }
            }
        }
        Prepared prepared = new Prepared(new DurationMatrix(durations, factors), drivers.stream().map(Driver::id).toList(),
                requests.stream().map(Request::id).toList());
        if (Thread.currentThread().isInterrupted()) {
            return new Result(null, Failure.INTERRUPTED, calls);
        }
        if (nanoTime.getAsLong() - started >= budgetNanos) {
            return new Result(null, Failure.BUDGET_EXCEEDED, calls);
        }
        return new Result(prepared, null, calls);
    }

    record Driver(UUID id, GeoPoint position) {
        Driver {
            Objects.requireNonNull(id);
            Objects.requireNonNull(position);
        }
    }

    record Request(UUID id, GeoPoint pickup, Set<UUID> eligibleDrivers) {
        Request {
            Objects.requireNonNull(id);
            Objects.requireNonNull(pickup);
            eligibleDrivers = Set.copyOf(eligibleDrivers);
        }
    }

    record Prepared(DurationMatrix matrix, List<UUID> driverIds, List<UUID> rideIds) {
        Prepared {
            driverIds = List.copyOf(driverIds);
            rideIds = List.copyOf(rideIds);
        }
    }

    record Result(Prepared prepared, Failure failure, int requests) {
    }

    enum Failure {
        EMPTY_BATCH, MATRIX_UNAVAILABLE, INVALID_MATRIX, CHANGED_TRAFFIC_FACTOR, BUDGET_EXCEEDED, INTERRUPTED
    }
}
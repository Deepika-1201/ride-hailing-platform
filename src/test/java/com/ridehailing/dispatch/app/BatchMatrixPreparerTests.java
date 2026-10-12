package com.ridehailing.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ridehailing.dispatch.app.BatchMatrixPreparer.Driver;
import com.ridehailing.dispatch.app.BatchMatrixPreparer.Failure;
import com.ridehailing.dispatch.app.BatchMatrixPreparer.Request;
import com.ridehailing.geography.RoutingProvider;
import com.ridehailing.geography.RoutingProvider.DurationMatrix;
import com.ridehailing.shared.GeoPoint;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BatchMatrixPreparerTests {

    private static final ZonedDateTime DEPARTURE = ZonedDateTime.parse("2026-10-12T12:00:00Z");
    private static final Duration BUDGET = Duration.ofSeconds(1);
    private final RoutingProvider routing = mock(RoutingProvider.class);
    private final AtomicLong nanos = new AtomicLong();
    private final BatchMatrixPreparer preparer = new BatchMatrixPreparer(routing, nanos::get);

    @Test
    void tilesTheFullWindowWithoutTransposingIdentitiesOrLosingFactors() {
        List<Driver> drivers = drivers(200);
        List<Request> rides = requests(200, drivers);
        AtomicInteger calls = new AtomicInteger();
        when(routing.matrix(any(), anyList(), anyList(), any())).thenAnswer(invocation -> {
            List<GeoPoint> origins = invocation.getArgument(1);
            List<GeoPoint> destinations = invocation.getArgument(2);
            assertThat(origins.size() + destinations.size()).isLessThanOrEqualTo(100);
            assertThat(invocation.<String>getArgument(0)).isEqualTo("blr");
            assertThat(invocation.<ZonedDateTime>getArgument(3)).isEqualTo(DEPARTURE);
            calls.incrementAndGet();
            return Optional.of(new DurationMatrix(origins.stream().map(origin -> destinations.stream()
                    .map(destination -> OptionalDouble.of(origin.lat() * 1000 + destination.lon())).toList()).toList(),
                    origins.stream().map(origin -> 1 + origin.lat() / 100).toList()));
        });

        var result = preparer.prepare("blr", drivers, rides, DEPARTURE, BUDGET);

        assertThat(result.failure()).isNull();
        assertThat(result.requests()).isEqualTo(16);
        assertThat(calls.get()).isEqualTo(16);
        assertThat(result.prepared().driverIds()).containsExactlyElementsOf(drivers.stream().map(Driver::id).toList());
        assertThat(result.prepared().rideIds()).containsExactlyElementsOf(rides.stream().map(Request::id).toList());
        for (int driver = 0; driver < 200; driver++) {
            for (int ride = 0; ride < 200; ride++) {
                assertThat(result.prepared().matrix().durationsS().get(driver).get(ride))
                        .hasValue(drivers.get(driver).position().lat() * 1000 + rides.get(ride).pickup().lon());
            }
            assertThat(result.prepared().matrix().trafficFactors().get(driver)).isEqualTo(1 + drivers.get(driver).position().lat() / 100);
        }
    }

    @Test
    void eachRideKeepsItsOwnEligibilityAndUnreachablePairsStayUnreachable() {
        List<Driver> drivers = drivers(2);
        List<Request> rides = new ArrayList<>(requests(2, drivers));
        rides.set(0, new Request(rides.getFirst().id(), rides.getFirst().pickup(), Set.of(drivers.getFirst().id())));
        when(routing.matrix(any(), anyList(), anyList(), any())).thenReturn(Optional.of(new DurationMatrix(List.of(
                List.of(OptionalDouble.of(1.25), OptionalDouble.empty()), List.of(OptionalDouble.of(0), OptionalDouble.of(2.75))))));

        var result = preparer.prepare("blr", drivers, rides, DEPARTURE, BUDGET);
        assertThat(result.prepared().matrix().durationsS()).containsExactly(
                List.of(OptionalDouble.of(1.25), OptionalDouble.empty()), List.of(OptionalDouble.empty(), OptionalDouble.of(2.75)));
        assertThat(result.prepared().matrix().trafficFactors()).containsExactly(1.0, 1.0);
    }

    @Test
    void unavailableOrChangedTilesNeverExposeAPartialMatrix() {
        List<Driver> drivers = drivers(1);
        List<Request> rides = requests(51, drivers);
        DurationMatrix first = new DurationMatrix(List.of(IntStream.range(0, 50).mapToObj(ignored -> OptionalDouble.of(10)).toList()), List.of(1.5));
        when(routing.matrix(any(), anyList(), anyList(), any())).thenReturn(Optional.of(first)).thenReturn(Optional.empty());
        var unavailable = preparer.prepare("blr", drivers, rides, DEPARTURE, BUDGET);
        assertThat(unavailable.failure()).isEqualTo(Failure.MATRIX_UNAVAILABLE);
        assertThat(unavailable.prepared()).isNull();
        assertThat(unavailable.requests()).isEqualTo(2);
        when(routing.matrix(any(), anyList(), anyList(), any())).thenReturn(Optional.of(first))
                .thenReturn(Optional.of(new DurationMatrix(List.of(List.of(OptionalDouble.of(10))), List.of(2.0))));
        var changed = preparer.prepare("blr", drivers, rides, DEPARTURE, BUDGET);
        assertThat(changed.failure()).isEqualTo(Failure.CHANGED_TRAFFIC_FACTOR);
        assertThat(changed.prepared()).isNull();
    }

    @Test
    void budgetAndInterruptionPreventFurtherRequestsWithoutSwallowingTheInterrupt() {
        List<Driver> drivers = drivers(1);
        List<Request> rides = requests(51, drivers);
        when(routing.matrix(any(), anyList(), anyList(), any())).thenAnswer(invocation -> {
            nanos.set(BUDGET.toNanos());
            return Optional.of(new DurationMatrix(List.of(IntStream.range(0, 50).mapToObj(ignored -> OptionalDouble.of(1)).toList())));
        });
        var late = preparer.prepare("blr", drivers, rides, DEPARTURE, BUDGET);
        assertThat(late.failure()).isEqualTo(Failure.BUDGET_EXCEEDED);
        assertThat(late.prepared()).isNull();
        assertThat(late.requests()).isEqualTo(1);
        try {
            Thread.currentThread().interrupt();
            var interrupted = preparer.prepare("blr", drivers, rides, DEPARTURE, BUDGET);
            assertThat(interrupted.failure()).isEqualTo(Failure.INTERRUPTED);
            assertThat(interrupted.requests()).isZero();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void finalAssemblyCannotReturnAResultAfterItsBudgetExpires() {
        List<Driver> drivers = drivers(1);
        List<Request> rides = requests(1, drivers);
        when(routing.matrix(any(), anyList(), anyList(), any())).thenReturn(Optional.of(
                new DurationMatrix(List.of(List.of(OptionalDouble.of(1))))));
        AtomicInteger checks = new AtomicInteger();
        BatchMatrixPreparer expiring = new BatchMatrixPreparer(routing,
                () -> checks.getAndIncrement() < 3 ? 0 : BUDGET.toNanos());

        var result = expiring.prepare("blr", drivers, rides, DEPARTURE, BUDGET);

        assertThat(result.failure()).isEqualTo(Failure.BUDGET_EXCEEDED);
        assertThat(result.prepared()).isNull();
        assertThat(result.requests()).isEqualTo(1);
    }

    @Test
    void malformedAndOutOfRangeRepliesAndProviderExceptionsDiscardTheWindow() {
        List<Driver> drivers = drivers(2);
        List<Request> rides = requests(2, drivers);
        for (DurationMatrix invalid : List.of(
                new DurationMatrix(List.of(List.of(OptionalDouble.of(1), OptionalDouble.of(2)))),
                new DurationMatrix(List.of(List.of(OptionalDouble.of(1)), List.of(OptionalDouble.of(2)))),
                new DurationMatrix(List.of(List.of(OptionalDouble.of((double) Integer.MAX_VALUE + 1), OptionalDouble.of(1)),
                        List.of(OptionalDouble.of(2), OptionalDouble.of(3)))))) {
            when(routing.matrix(any(), anyList(), anyList(), any())).thenReturn(Optional.of(invalid));
            var result = preparer.prepare("blr", drivers, rides, DEPARTURE, BUDGET);
            assertThat(result.failure()).isEqualTo(Failure.INVALID_MATRIX);
            assertThat(result.prepared()).isNull();
            assertThat(result.requests()).isEqualTo(1);
        }
        when(routing.matrix(any(), anyList(), anyList(), any())).thenThrow(new IllegalStateException("provider unavailable"));
        var failed = preparer.prepare("blr", drivers, rides, DEPARTURE, BUDGET);
        assertThat(failed.failure()).isEqualTo(Failure.MATRIX_UNAVAILABLE);
        assertThat(failed.prepared()).isNull();
        assertThat(failed.requests()).isEqualTo(1);
    }

    @Test
    void anInterruptedProviderDoesNotReturnPreparedWorkOrClearTheFlag() {
        List<Driver> drivers = drivers(1);
        when(routing.matrix(any(), anyList(), anyList(), any())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return Optional.empty();
        });
        try {
            var result = preparer.prepare("blr", drivers, requests(1, drivers), DEPARTURE, BUDGET);
            assertThat(result.failure()).isEqualTo(Failure.INTERRUPTED);
            assertThat(result.prepared()).isNull();
            assertThat(result.requests()).isEqualTo(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsAmbiguousOversizedAndUnknownInputsBeforeCallingRouting() {
        List<Driver> drivers = drivers(1);
        List<Request> rides = requests(1, drivers);
        assertThatThrownBy(() -> preparer.prepare("blr", List.of(drivers.getFirst(), drivers.getFirst()), rides, DEPARTURE, BUDGET))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preparer.prepare("blr", drivers, List.of(rides.getFirst(), rides.getFirst()), DEPARTURE, BUDGET))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preparer.prepare("blr", drivers, requests(1, drivers(2)), DEPARTURE, BUDGET))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preparer.prepare("blr", drivers(201), rides, DEPARTURE, BUDGET)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preparer.prepare("blr", drivers, requests(201, drivers), DEPARTURE, BUDGET))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preparer.prepare("blr", drivers, rides, DEPARTURE, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preparer.prepare("blr", drivers, rides, DEPARTURE, Duration.ofSeconds(6)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(preparer.prepare("blr", List.of(), List.of(), DEPARTURE, BUDGET).failure()).isEqualTo(Failure.EMPTY_BATCH);
        verifyNoInteractions(routing);
    }

    private static List<Driver> drivers(int count) {
        return IntStream.range(0, count).mapToObj(index -> new Driver(new UUID(0, index + 1), new GeoPoint(12 + index * 0.001, 77))).toList();
    }

    private static List<Request> requests(int count, List<Driver> drivers) {
        Set<UUID> eligible = drivers.stream().map(Driver::id).collect(Collectors.toSet());
        return IntStream.range(0, count).mapToObj(index -> new Request(new UUID(1, index + 1), new GeoPoint(13, 78 + index * 0.001), eligible)).toList();
    }
}
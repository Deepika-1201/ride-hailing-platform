package com.ridehailing.location.trips;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.location.TripRoutes.RoutePoint;
import com.ridehailing.location.TripRoutes.TripRoute;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/** LLD §9.8: a route is thinned to evenly spaced points that keep both ends. */
class RouteThinningTests {

    @Property
    void aThinnedRouteKeepsTheLimitInOrderWithBothEnds(@ForAll @IntRange(min = 0, max = 10_000) int n,
            @ForAll @IntRange(min = 2, max = 3_000) int max) {
        List<Integer> points = IntStream.range(0, n).boxed().toList();

        List<Integer> kept = TripRouteService.thin(points, max);

        if (n <= max) {
            assertThat(kept).isEqualTo(points);
            return;
        }
        assertThat(kept).hasSize(max).isSorted().doesNotHaveDuplicates();
        assertThat(kept.getFirst()).isZero();
        assertThat(kept.getLast()).isEqualTo(n - 1);
        int widest = Math.ceilDiv(n - 1, max - 1);
        assertThat(IntStream.range(1, max).map(i -> kept.get(i) - kept.get(i - 1))).allMatch(gap -> gap <= widest);
    }

    @Example
    void fourThousandPointsKeepEveryOtherAndTheLast() {
        List<Integer> kept = TripRouteService.thin(IntStream.range(0, 4_000).boxed().toList(), 2_000);

        assertThat(kept).hasSize(2_000).startsWith(0, 2, 4).endsWith(3_995, 3_997, 3_999);
    }

    @Example
    void aRouteSaysItWasThinnedOnlyWhenItHadMorePointsThanTheLimit() {
        UUID ride = UUID.randomUUID();

        TripRoute atTheLimit = new TripRouteService(storedPoints(3)).route(ride, 3);
        TripRoute overIt = new TripRouteService(storedPoints(4)).route(ride, 3);

        assertThat(atTheLimit.thinned()).isFalse();
        assertThat(atTheLimit.points()).extracting(RoutePoint::seq).containsExactly(0L, 1L, 2L);
        assertThat(overIt.thinned()).isTrue();
        assertThat(overIt.points()).extracting(RoutePoint::seq).containsExactly(0L, 2L, 3L);
    }

    private static TripPointRepository storedPoints(int count) {
        return new TripPointRepository(null) {
            @Override
            public List<RoutePoint> route(UUID rideId) {
                return LongStream.range(0, count).mapToObj(seq -> new RoutePoint(seq, 12.9, 77.6, Instant.EPOCH))
                        .toList();
            }
        };
    }
}

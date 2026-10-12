package com.ridehailing.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ridehailing.geography.RoutingProvider;
import com.ridehailing.geography.RoutingProvider.DurationMatrix;
import com.ridehailing.location.LiveIndex.Candidate;
import com.ridehailing.shared.GeoPoint;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EtaRankerTests {

    private static final GeoPoint PICKUP = new GeoPoint(12.97, 77.59);
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final CandidateRanker.Context CONTEXT = new CandidateRanker.Context("blr", PICKUP,
            ZonedDateTime.parse("2026-10-12T17:30:00+05:30[Asia/Kolkata]"));

    private final RoutingProvider routing = mock(RoutingProvider.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final EtaRanker ranker = new EtaRanker(routing, new NearestDriverRanker(), metrics);

    @Test
    void aFartherDriverWithALowerEtaRanksFirstWithoutRoundingTheScore() {
        Candidate near = candidate(1, 100, NOW);
        Candidate far = candidate(2, 900, NOW);
        table(List.of(OptionalDouble.of(10.9), OptionalDouble.of(10.1)));

        var result = ranker.rank(CONTEXT, List.of(far, near));

        assertThat(result.candidates()).extracting(CandidateRanker.Ranked::candidate).containsExactly(far, near);
        assertThat(result.candidates()).extracting(CandidateRanker.Ranked::score).containsExactly(10.1, 10.9);
        assertThat(result.scoreUnit()).isEqualTo("SECONDS");
        assertThat(result.fallbackReason()).isNull();
        verify(routing).matrix("blr", List.of(near.position(), far.position()), List.of(PICKUP), CONTEXT.departure());
    }

    @Test
    void equalEtasUseDistanceFreshnessAndDriverIdForDeterministicTies() {
        Candidate near = candidate(4, 100, NOW.minusSeconds(20));
        Candidate stale = candidate(1, 200, NOW.minusSeconds(1));
        Candidate lower = candidate(2, 200, NOW);
        Candidate higher = candidate(3, 200, NOW);
        table(java.util.Collections.nCopies(4, OptionalDouble.of(10)));

        assertThat(ranker.rank(CONTEXT, List.of(higher, stale, lower, near)).candidates())
                .extracting(CandidateRanker.Ranked::candidate).containsExactly(near, lower, higher, stale);
    }

    @Test
    void onlyTheNearestTenCandidatesEnterTheTable() {
        List<Candidate> candidates = new ArrayList<>();
        for (int index = 11; index >= 1; index--) {
            candidates.add(candidate(index, index * 100, NOW));
        }
        table(java.util.Collections.nCopies(10, OptionalDouble.of(1)));

        var result = ranker.rank(CONTEXT, candidates);

        assertThat(result.candidates()).hasSize(10);
        assertThat(result.outsideLimit()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GeoPoint>> origins = ArgumentCaptor.forClass(List.class);
        verify(routing).matrix(eq("blr"), origins.capture(), eq(List.of(PICKUP)), eq(CONTEXT.departure()));
        assertThat(origins.getValue()).containsExactlyElementsOf(IntStream.rangeClosed(1, 10)
                .mapToObj(index -> candidate(index, index * 100, NOW).position()).toList());
    }

    @Test
    void unreachablePairsAreExcludedButAZeroEtaIsValid() {
        Candidate near = candidate(1, 100, NOW);
        Candidate far = candidate(2, 200, NOW);
        table(List.of(OptionalDouble.empty(), OptionalDouble.of(0)));

        var result = ranker.rank(CONTEXT, List.of(near, far));

        assertThat(result.candidates()).singleElement().satisfies(ranked -> {
            assertThat(ranked.candidate()).isEqualTo(far);
            assertThat(ranked.score()).isZero();
        });
        assertThat(result.unreachable()).isEqualTo(1);
        assertThat(result.fallbackReason()).isNull();
        table(List.of(OptionalDouble.empty(), OptionalDouble.empty()));
        assertThat(ranker.rank(CONTEXT, List.of(near, far)).candidates()).isEmpty();
    }

    @Test
    void unavailableAndMalformedMatricesFallBackToTheCompleteDistanceOrder() {
        List<Candidate> candidates = IntStream.rangeClosed(1, 12).mapToObj(index -> candidate(index, index * 100, NOW)).toList();
        when(routing.matrix(any(), anyList(), anyList(), any())).thenReturn(Optional.empty());

        var fallback = ranker.rank(CONTEXT, candidates.reversed());

        assertThat(fallback.candidates()).extracting(CandidateRanker.Ranked::candidate).containsExactlyElementsOf(candidates);
        assertThat(fallback.scoreUnit()).isEqualTo("METRES");
        assertThat(fallback.fallbackReason()).isEqualTo("MATRIX_UNAVAILABLE");
        assertThat(fallback.outsideLimit()).isZero();
        table(List.of(OptionalDouble.of(1)));
        assertThat(ranker.rank(CONTEXT, candidates).fallbackReason()).isEqualTo("INVALID_MATRIX");
        when(routing.matrix(any(), anyList(), anyList(), any())).thenThrow(new IllegalStateException("routing unavailable"));
        assertThat(ranker.rank(CONTEXT, candidates).fallbackReason()).isEqualTo("MATRIX_UNAVAILABLE");
        assertThat(metrics.get("dispatch.ranking").tag("outcome", "fallback").counter().count()).isEqualTo(3);
    }

    @Test
    void emptyCandidateListsDoNotCallRouting() {
        assertThat(ranker.rank(CONTEXT, List.of()).candidates()).isEmpty();
        verifyNoInteractions(routing);
    }

    private void table(List<OptionalDouble> durations) {
        when(routing.matrix(any(), anyList(), any(), any())).thenReturn(Optional.of(new DurationMatrix(
                durations.stream().map(List::of).toList())));
    }

    private static Candidate candidate(int id, int distance, Instant seen) {
        return new Candidate(new UUID(0, id), new GeoPoint(PICKUP.lat() + id * 0.0001, PICKUP.lon()), distance, seen);
    }
}
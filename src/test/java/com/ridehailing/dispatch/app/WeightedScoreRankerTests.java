package com.ridehailing.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ridehailing.dispatch.db.DriverStatsRepository;
import com.ridehailing.dispatch.db.DriverStatsRepository.RankingStats;
import com.ridehailing.geography.RankingWeights;
import com.ridehailing.geography.RoutingProvider;
import com.ridehailing.geography.RoutingProvider.DurationMatrix;
import com.ridehailing.location.LiveIndex.Candidate;
import com.ridehailing.shared.GeoPoint;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class WeightedScoreRankerTests {

    private static final GeoPoint PICKUP = new GeoPoint(0.01, 0);
    private static final Candidate NEAR = new Candidate(new UUID(0, 1), new GeoPoint(0, 0), 100,
            Instant.parse("2026-10-12T12:00:00Z"), 0.0, 5.0);
    private static final Candidate FAR = new Candidate(new UUID(0, 2), new GeoPoint(-0.01, 0), 200,
            NEAR.lastSeen(), 180.0, 5.0);
    private static final CandidateRanker.Context CONTEXT = new CandidateRanker.Context("blr", PICKUP,
            ZonedDateTime.parse("2026-10-12T17:30:00+05:30[Asia/Kolkata]"));

    private final RoutingProvider routing = mock(RoutingProvider.class);
    private final DriverStatsRepository stats = mock(DriverStatsRepository.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final WeightedScoreRanker ranker = new WeightedScoreRanker(new EtaRanker(routing, new NearestDriverRanker(), metrics), stats, metrics);

    WeightedScoreRankerTests() {
        when(routing.matrix(any(), anyList(), anyList(), any())).thenReturn(Optional.of(new DurationMatrix(
                List.of(List.of(OptionalDouble.of(10)), List.of(OptionalDouble.of(11))))));
        when(stats.rankingInputs(any(), anyList())).thenReturn(Map.of());
    }

    @Test
    void acceptanceAndIdleCanChangeTheEtaOrderWithInspectableComponents() {
        when(stats.rankingInputs(any(), anyList())).thenReturn(Map.of(
                NEAR.driverId(), new RankingStats(0, 90, 0, 0), FAR.driverId(), new RankingStats(90, 0, 0, 20)));

        var result = ranker.rank(CONTEXT, List.of(FAR, NEAR));

        assertThat(result.candidates()).extracting(CandidateRanker.Ranked::candidate).containsExactly(FAR, NEAR);
        assertThat(result.scoreUnit()).isEqualTo("WEIGHTED_SECONDS");
        assertThat(result.candidates().getFirst().weightedScore().weights()).isEqualTo(RankingWeights.DEFAULT);
        assertThat(result.candidates().getFirst().weightedScore().accepted()).isEqualTo(90);
        assertThat(result.candidates().getFirst().weightedScore().idleCredit()).isEqualTo(40);
        verify(stats).rankingInputs("blr", List.of(NEAR.driverId(), FAR.driverId()));
        assertThat(metrics.get("dispatch.ranking").tag("strategy", "weighted").tag("outcome", "success").counter().count()).isEqualTo(1);
        assertThat(metrics.get("dispatch.ranking").tag("strategy", "eta").tag("outcome", "success").counter().count()).isZero();
    }

    @Test
    void categoryWeightsCanReduceThePolicyToPureEta() {
        CandidateRanker.Context etaOnly = new CandidateRanker.Context("blr", PICKUP, CONTEXT.departure(), new RankingWeights(1, 0, 0, 0));

        var result = ranker.rank(etaOnly, List.of(FAR, NEAR));

        assertThat(result.candidates()).extracting(CandidateRanker.Ranked::candidate).containsExactly(NEAR, FAR);
        assertThat(result.candidates()).extracting(CandidateRanker.Ranked::score).containsExactly(10.0, 11.0);
        assertThat(result.candidates().getFirst().weightedScore().acceptanceRate()).isEqualTo(0.8);
    }

    @Test
    void unavailableRoadTimesKeepDistanceFallbackAndDoNotQueryStatistics() {
        when(routing.matrix(any(), anyList(), anyList(), any())).thenReturn(Optional.empty());

        var result = ranker.rank(CONTEXT, List.of(FAR, NEAR));

        assertThat(result.scoreUnit()).isEqualTo("METRES");
        assertThat(result.fallbackReason()).isEqualTo("MATRIX_UNAVAILABLE");
        assertThat(result.candidates()).extracting(CandidateRanker.Ranked::candidate).containsExactly(NEAR, FAR);
        assertThat(result.candidates()).allSatisfy(entry -> assertThat(entry.weightedScore()).isNull());
        verifyNoInteractions(stats);
    }

    @Test
    void unreachableOrEmptyCandidatesDoNotReadStatistics() {
        assertThat(ranker.rank(CONTEXT, List.of()).candidates()).isEmpty();
        when(routing.matrix(any(), anyList(), anyList(), any())).thenReturn(Optional.of(new DurationMatrix(
                List.of(List.of(OptionalDouble.empty()), List.of(OptionalDouble.empty())))));

        var result = ranker.rank(CONTEXT, List.of(NEAR, FAR));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.unreachable()).isEqualTo(2);
        verifyNoInteractions(stats);
    }

    @Test
    void databaseFailurePropagatesInsteadOfContinuingAnAbortedSearchTransaction() {
        when(stats.rankingInputs(any(), anyList())).thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> ranker.rank(CONTEXT, List.of(NEAR, FAR))).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
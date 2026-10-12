package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.db.DriverStatsRepository;
import com.ridehailing.dispatch.db.DriverStatsRepository.RankingStats;
import com.ridehailing.location.LiveIndex.Candidate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class WeightedScoreRanker implements CandidateRanker {

    private final EtaRanker eta;
    private final DriverStatsRepository stats;
    private final Counter successful;
    private final Counter fallback;

    WeightedScoreRanker(EtaRanker eta, DriverStatsRepository stats, MeterRegistry metrics) {
        this.eta = eta;
        this.stats = stats;
        successful = metrics.counter("dispatch.ranking", "strategy", "weighted", "outcome", "success");
        fallback = metrics.counter("dispatch.ranking", "strategy", "weighted", "outcome", "fallback");
    }

    @Override
    public String name() {
        return "weighted";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public Ranking rank(Context context, List<Candidate> candidates) {
        Ranking road = eta.roadOrder(context, candidates);
        if (road.fallbackReason() != null) {
            fallback.increment();
            return road;
        }
        Map<UUID, RankingStats> inputs = road.candidates().isEmpty() ? Map.of()
                : stats.rankingInputs(context.cityId(), road.candidates().stream().map(entry -> entry.candidate().driverId()).toList());
        List<Ranked> ranked = road.candidates().stream().map(entry -> {
            Candidate candidate = entry.candidate();
            RankingStats observed = inputs.getOrDefault(candidate.driverId(), RankingStats.NEW);
            WeightedScoring.Score score = WeightedScoring.score(context.rankingWeights(), entry.score(), observed.accepted(),
                    observed.declined(), observed.expired(), observed.idleMinutes(), candidate.position(), context.pickup(),
                    candidate.headingDeg(), candidate.speedMps());
            return new Ranked(candidate, score.total(), score, entry.trafficMultiplier());
        }).sorted(Comparator.comparingDouble(Ranked::score).thenComparing(Ranked::candidate, NearestDriverRanker.ORDER)).toList();
        if (!candidates.isEmpty()) {
            successful.increment();
        }
        return new Ranking(ranked, "WEIGHTED_SECONDS", null, road.unreachable(), road.outsideLimit());
    }
}
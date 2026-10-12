package com.ridehailing.dispatch.app;

import com.ridehailing.geography.RoutingProvider;
import com.ridehailing.geography.RoutingProvider.DurationMatrix;
import com.ridehailing.location.LiveIndex.Candidate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.springframework.stereotype.Component;

@Component
class EtaRanker implements CandidateRanker {

    static final int CANDIDATE_LIMIT = 10;

    private final RoutingProvider routing;
    private final NearestDriverRanker nearest;
    private final Counter successful;
    private final Counter unavailable;

    EtaRanker(RoutingProvider routing, NearestDriverRanker nearest, MeterRegistry metrics) {
        this.routing = routing;
        this.nearest = nearest;
        successful = metrics.counter("dispatch.ranking", "strategy", "eta", "outcome", "success");
        unavailable = metrics.counter("dispatch.ranking", "strategy", "eta", "outcome", "fallback");
    }

    @Override
    public String name() {
        return "eta";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public Ranking rank(Context context, List<Candidate> candidates) {
        Ranking ranking = roadOrder(context, candidates);
        if (!candidates.isEmpty()) {
            (ranking.fallbackReason() == null ? successful : unavailable).increment();
        }
        return ranking;
    }

    Ranking roadOrder(Context context, List<Candidate> candidates) {
        List<Candidate> considered = candidates.stream().sorted(NearestDriverRanker.ORDER).limit(CANDIDATE_LIMIT).toList();
        if (considered.isEmpty()) {
            return new Ranking(List.of(), "SECONDS", null, 0, 0);
        }
        Optional<DurationMatrix> matrix;
        try {
            matrix = routing.matrix(context.cityId(), considered.stream().map(Candidate::position).toList(),
                    List.of(context.pickup()), context.departure());
        } catch (RuntimeException failure) {
            return fallback(context, candidates, "MATRIX_UNAVAILABLE");
        }
        if (matrix.isEmpty()) {
            return fallback(context, candidates, "MATRIX_UNAVAILABLE");
        }
        List<List<OptionalDouble>> durations = matrix.get().durationsS();
        if (durations.size() != considered.size() || durations.stream().anyMatch(row -> row.size() != 1)) {
            return fallback(context, candidates, "INVALID_MATRIX");
        }
        List<Ranked> ranked = new ArrayList<>();
        for (int index = 0; index < considered.size(); index++) {
            OptionalDouble duration = durations.get(index).getFirst();
            if (duration.isPresent()) {
                ranked.add(new Ranked(considered.get(index), duration.getAsDouble()));
            }
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).thenComparing(Ranked::candidate, NearestDriverRanker.ORDER));
        return new Ranking(ranked, "SECONDS", null, considered.size() - ranked.size(), candidates.size() - considered.size());
    }

    private Ranking fallback(Context context, List<Candidate> candidates, String reason) {
        Ranking ranking = nearest.rank(context, candidates);
        return new Ranking(ranking.candidates(), ranking.scoreUnit(), reason, 0, 0);
    }
}
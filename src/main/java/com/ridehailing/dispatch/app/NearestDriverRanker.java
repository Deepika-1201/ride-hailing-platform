package com.ridehailing.dispatch.app;

import com.ridehailing.location.LiveIndex.Candidate;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * V1's ranker (LLD §8.11): straight-line distance; ties go to the fresher position, then the lower driver ID, so the
 * order is deterministic.
 */
@Component
class NearestDriverRanker implements CandidateRanker {

    static final String NAME = "nearest";

    private static final Comparator<Candidate> ORDER = Comparator.comparingInt(Candidate::distanceM)
            .thenComparing(Candidate::lastSeen, Comparator.reverseOrder())
            .thenComparing(Candidate::driverId);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public List<Ranked> rank(List<Candidate> candidates) {
        return candidates.stream().sorted(ORDER).map(candidate -> new Ranked(candidate, candidate.distanceM())).toList();
    }
}

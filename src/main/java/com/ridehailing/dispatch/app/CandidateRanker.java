package com.ridehailing.dispatch.app;

import com.ridehailing.location.LiveIndex.Candidate;
import com.ridehailing.geography.RankingWeights;
import com.ridehailing.shared.GeoPoint;
import java.time.ZonedDateTime;
import java.util.List;

/** Orders a search's candidates, best first (LLD §8.11); chosen per city and category. */
interface CandidateRanker {

    String name();

    String version();

    Ranking rank(Context context, List<Candidate> candidates);

    record Context(String cityId, GeoPoint pickup, ZonedDateTime departure, RankingWeights rankingWeights) {

        Context(String cityId, GeoPoint pickup, ZonedDateTime departure) {
            this(cityId, pickup, departure, RankingWeights.DEFAULT);
        }
    }

    record Ranking(List<Ranked> candidates, String scoreUnit, String fallbackReason, int unreachable, int outsideLimit) {

        public Ranking {
            candidates = List.copyOf(candidates);
        }
    }

    /** Lower scores rank first. */
    record Ranked(Candidate candidate, double score, WeightedScoring.Score weightedScore, Double trafficMultiplier) {

        Ranked(Candidate candidate, double score) {
            this(candidate, score, null, null);
        }

        Ranked(Candidate candidate, double score, WeightedScoring.Score weightedScore) {
            this(candidate, score, weightedScore, null);
        }
    }
}

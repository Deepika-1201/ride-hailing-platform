package com.ridehailing.dispatch.app;

import com.ridehailing.location.LiveIndex.Candidate;
import java.util.List;

/** Orders a search's candidates, best first (LLD §8.11); chosen per city and category. */
interface CandidateRanker {

    String name();

    String version();

    List<Ranked> rank(List<Candidate> candidates);

    /** Lower scores rank first. */
    record Ranked(Candidate candidate, double score) {
    }
}

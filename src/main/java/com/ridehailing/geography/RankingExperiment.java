package com.ridehailing.geography;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RankingExperiment(@JsonProperty(required = true) String id, @JsonProperty(required = true) String ranker,
        @JsonProperty(required = true) int percentage) {

    public RankingExperiment {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,63}")
                || (!"nearest".equals(ranker) && !"eta".equals(ranker) && !"weighted".equals(ranker))
                || percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("Ranking experiments require a valid ID, nearest/eta/weighted ranker and percentage in 0..100");
        }
    }
}
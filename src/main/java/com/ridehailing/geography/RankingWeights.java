package com.ridehailing.geography;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RankingWeights(@JsonProperty(required = true) double eta, @JsonProperty(required = true) double acceptance,
    @JsonProperty(required = true) double idle, @JsonProperty(required = true) double heading) {

    public static final RankingWeights DEFAULT = new RankingWeights(1, 60, 2, 30);

    public RankingWeights {
        if (!Double.isFinite(eta) || eta <= 0 || eta > 1000
                || !Double.isFinite(acceptance) || acceptance < 0 || acceptance > 3600
                || !Double.isFinite(idle) || idle < 0 || idle > 60
                || !Double.isFinite(heading) || heading < 0 || heading > 3600) {
            throw new IllegalArgumentException("Ranking weights require eta in (0,1000], acceptance/heading in [0,3600], and idle in [0,60]");
        }
    }
}
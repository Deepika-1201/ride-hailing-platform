package com.ridehailing.dispatch.app;

import com.ridehailing.geography.RankingExperiment;
import com.ridehailing.geography.RankingWeights;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public record RankerAssignment(String strategy, int categoryVersion, RankingWeights weights, Experiment experiment) {

    private static final String ALGORITHM = "sha256-ride-v1";

    public static RankerAssignment choose(UUID rideId, String cityId, String category, String control, int categoryVersion,
            RankingWeights weights, RankingExperiment experiment) {
        if (experiment == null) {
            return new RankerAssignment(control, categoryVersion, weights, null);
        }
        String input = ALGORITHM + "\n" + experiment.id() + "\n" + cityId + "\n" + category + "\n" + rideId;
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
        int bucket = Integer.remainderUnsigned(ByteBuffer.wrap(digest).getInt(), 10_000);
        String strategy = bucket < experiment.percentage() * 100 ? experiment.ranker() : control;
        return new RankerAssignment(strategy, categoryVersion, weights,
                new Experiment(experiment.id(), control, experiment.ranker(), experiment.percentage(), bucket, ALGORITHM));
    }

    public record Experiment(String id, String control, String treatment, int percentage, int bucket, String algorithm) {
    }
}
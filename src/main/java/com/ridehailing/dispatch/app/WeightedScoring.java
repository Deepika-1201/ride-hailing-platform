package com.ridehailing.dispatch.app;

import com.ridehailing.geography.RankingWeights;
import com.ridehailing.shared.GeoPoint;

final class WeightedScoring {

    static final double PRIOR_ACCEPTED = 8;
    static final double PRIOR_RESOLVED = 10;
    static final double MAX_IDLE_MINUTES = 30;
    static final double MIN_HEADING_SPEED_MPS = 1;

    private WeightedScoring() {
    }

    static Score score(RankingWeights weights, double etaSeconds, long accepted, long declined, long expired,
            double idleMinutes, GeoPoint position, GeoPoint pickup, Double headingDeg, Double speedMps) {
        if (!Double.isFinite(etaSeconds) || etaSeconds < 0 || etaSeconds > Integer.MAX_VALUE || accepted < 0 || declined < 0 || expired < 0
                || !Double.isFinite(idleMinutes)) {
            throw new IllegalArgumentException("Weighted ranking inputs must contain valid ETA, outcome counts and idle duration");
        }
        double acceptanceRate = (accepted + PRIOR_ACCEPTED) / (accepted + (double) declined + expired + PRIOR_RESOLVED);
        double boundedIdle = Math.clamp(idleMinutes, 0, MAX_IDLE_MINUTES);
        double headingPenalty = headingPenalty(position, pickup, headingDeg, speedMps);
        double etaCost = weights.eta() * etaSeconds;
        double acceptanceCost = weights.acceptance() * (1 - acceptanceRate);
        double idleCredit = weights.idle() * boundedIdle;
        double headingCost = weights.heading() * headingPenalty;
        return new Score(etaCost + acceptanceCost - idleCredit + headingCost, weights, etaSeconds, accepted, declined, expired,
            acceptanceRate, boundedIdle, headingPenalty, etaCost, acceptanceCost, idleCredit, headingCost);
    }

    private static double headingPenalty(GeoPoint position, GeoPoint pickup, Double headingDeg, Double speedMps) {
        if (position.metresTo(pickup) < 1) {
            return 0;
        }
        if (headingDeg == null || speedMps == null || !Double.isFinite(headingDeg) || !Double.isFinite(speedMps)
                || headingDeg < 0 || headingDeg >= 360 || speedMps < MIN_HEADING_SPEED_MPS) {
            return 0.5;
        }
        double originLatitude = Math.toRadians(position.lat());
        double targetLatitude = Math.toRadians(pickup.lat());
        double longitudeDelta = Math.toRadians(pickup.lon() - position.lon());
        double bearing = Math.atan2(Math.sin(longitudeDelta) * Math.cos(targetLatitude),
                Math.cos(originLatitude) * Math.sin(targetLatitude)
                        - Math.sin(originLatitude) * Math.cos(targetLatitude) * Math.cos(longitudeDelta));
        return Math.clamp((1 - Math.cos(Math.toRadians(headingDeg) - bearing)) / 2, 0, 1);
    }

        record Score(double total, RankingWeights weights, double etaSeconds, long accepted, long declined, long expired,
            double acceptanceRate, double idleMinutes, double headingPenalty,
            double etaCost, double acceptanceCost, double idleCredit, double headingCost) {
    }
}
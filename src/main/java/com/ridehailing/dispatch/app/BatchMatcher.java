package com.ridehailing.dispatch.app;

import com.ridehailing.geography.RoutingProvider.DurationMatrix;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import org.jgrapht.alg.matching.KuhnMunkresMinimalWeightBipartitePerfectMatching;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

final class BatchMatcher {

    static final int MAX_SIZE = 200;

    Plan solve(DurationMatrix matrix) {
        double maximum = validate(matrix);
        int drivers = matrix.durationsS().size();
        int rides = matrix.durationsS().getFirst().size();
        int size = Math.max(drivers, rides);
        double unmatchedCost = (maximum + 1) * (size + 1);
        var graph = new SimpleWeightedGraph<Integer, DefaultWeightedEdge>(DefaultWeightedEdge.class);
        Set<Integer> driverVertices = new LinkedHashSet<>();
        Set<Integer> rideVertices = new LinkedHashSet<>();
        for (int index = 0; index < size; index++) {
            graph.addVertex(index);
            graph.addVertex(size + index);
            driverVertices.add(index);
            rideVertices.add(size + index);
        }
        for (int driver = 0; driver < size; driver++) {
            for (int ride = 0; ride < size; ride++) {
                double cost = ride >= rides ? 0 : unmatchedCost;
                if (driver < drivers && ride < rides && matrix.durationsS().get(driver).get(ride).isPresent()) {
                    cost = matrix.durationsS().get(driver).get(ride).getAsDouble();
                }
                DefaultWeightedEdge edge = graph.addEdge(driver, size + ride);
                graph.setEdgeWeight(edge, cost);
            }
        }
        var matching = new KuhnMunkresMinimalWeightBipartitePerfectMatching<>(graph, driverVertices, rideVertices).getMatching();
        List<Match> matches = new ArrayList<>();
        for (DefaultWeightedEdge edge : matching.getEdges()) {
            int driver = graph.getEdgeSource(edge);
            int ride = graph.getEdgeTarget(edge) - size;
            if (driver < drivers && ride < rides && matrix.durationsS().get(driver).get(ride).isPresent()) {
                matches.add(new Match(driver, ride, matrix.durationsS().get(driver).get(ride).getAsDouble()));
            }
        }
        return new Plan(matches.stream().sorted(Comparator.comparingInt(Match::ride)).toList(), drivers, rides);
    }

    private static double validate(DurationMatrix matrix) {
        int drivers = matrix.durationsS().size();
        int rides = matrix.durationsS().getFirst().size();
        if (drivers > MAX_SIZE || rides > MAX_SIZE) {
            throw new IllegalArgumentException("Exact batch matching accepts at most 200 drivers and 200 rides");
        }
        double maximum = 0;
        for (List<OptionalDouble> row : matrix.durationsS()) {
            for (OptionalDouble duration : row) {
                if (duration.isPresent()) {
                    if (duration.getAsDouble() > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException("Batch pickup ETA exceeds the supported duration bound");
                    }
                    maximum = Math.max(maximum, duration.getAsDouble());
                }
            }
        }
        return maximum;
    }

    Plan sequential(DurationMatrix matrix) {
        validate(matrix);
        boolean[] used = new boolean[matrix.durationsS().size()];
        List<Match> matches = new ArrayList<>();
        int rides = matrix.durationsS().getFirst().size();
        for (int ride = 0; ride < rides; ride++) {
            Match best = null;
            for (int driver = 0; driver < used.length; driver++) {
                OptionalDouble eta = matrix.durationsS().get(driver).get(ride);
                if (!used[driver] && eta.isPresent() && (best == null || eta.getAsDouble() < best.etaSeconds())) {
                    best = new Match(driver, ride, eta.getAsDouble());
                }
            }
            if (best != null) {
                used[best.driver()] = true;
                matches.add(best);
            }
        }
        return new Plan(matches, used.length, rides);
    }

    record Match(int driver, int ride, double etaSeconds) {
    }

    record Plan(List<Match> matches, int drivers, int rides) {
        Plan {
            matches = List.copyOf(matches);
        }

        double totalEtaSeconds() {
            return matches.stream().mapToDouble(Match::etaSeconds).sum();
        }

        OptionalDouble meanEtaSeconds() {
            return matches.stream().mapToDouble(Match::etaSeconds).average();
        }

        int unmatchedRides() {
            return rides - matches.size();
        }
    }
}
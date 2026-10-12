package com.ridehailing.dispatch.app;

import com.ridehailing.geography.RoutingProvider.DurationMatrix;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalDouble;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

public final class BatchMatchingExperiment {

    private static final int MAX_INPUT_BYTES = 2 * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    private BatchMatchingExperiment() {
    }

    public static void main(String[] arguments) throws IOException, NoSuchAlgorithmException {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Provide a matrix input JSON path and a separate report output path");
        }
        Path input = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path output = Path.of(arguments[1]).toAbsolutePath().normalize();
        if (input.equals(output) || (Files.exists(output) && Files.isSameFile(input, output))) {
            throw new IllegalArgumentException("The report must not overwrite its matrix input");
        }
        byte[] bytes;
        try (var stream = Files.newInputStream(input)) {
            bytes = stream.readNBytes(MAX_INPUT_BYTES + 1);
        }
        if (bytes.length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("Matrix input exceeds 2 MiB");
        }
        Input data = JSON.readValue(bytes, Input.class);
        DurationMatrix matrix = new DurationMatrix(data.durationsS().stream().map(row -> row.stream()
                .map(value -> value == null ? OptionalDouble.empty() : OptionalDouble.of(value)).toList()).toList());
        BatchMatcher matcher = new BatchMatcher();
        long started = System.nanoTime();
        var sequential = matcher.sequential(matrix);
        long sequentialNanos = System.nanoTime() - started;
        started = System.nanoTime();
        var optimal = matcher.solve(matrix);
        long optimalNanos = System.nanoTime() - started;
        boolean sameRides = sequential.matches().stream().map(BatchMatcher.Match::ride).sorted().toList()
                .equals(optimal.matches().stream().map(BatchMatcher.Match::ride).sorted().toList());
        Double meanReduction = sameRides && sequential.meanEtaSeconds().orElse(0) > 0
                ? 100 * (1 - optimal.meanEtaSeconds().orElseThrow() / sequential.meanEtaSeconds().orElseThrow()) : null;
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Report report = new Report(data.name(), "OFFLINE_MATRIX_COMPARISON_NOT_ADOPTION", digest, optimal.drivers(), optimal.rides(),
                summary(sequential, sequentialNanos), summary(optimal, optimalNanos), sameRides, meanReduction);
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + System.lineSeparator());
        System.out.println("Wrote offline matching comparison to " + output);
    }

    private static Summary summary(BatchMatcher.Plan plan, long elapsedNanos) {
        return new Summary(plan.matches(), plan.unmatchedRides(), (double) plan.matches().size() / plan.rides(),
                plan.totalEtaSeconds(), plan.meanEtaSeconds().isPresent() ? plan.meanEtaSeconds().getAsDouble() : null,
                elapsedNanos / 1e6);
    }

    record Input(String name, List<List<Double>> durationsS) {
        Input {
            if (name == null || name.isBlank() || name.length() > 200 || durationsS == null) {
                throw new IllegalArgumentException("Matrix input requires a short name and durations_s");
            }
        }
    }

    record Summary(List<BatchMatcher.Match> matches, int unmatchedRides, double matchRate, double totalEtaSeconds,
            Double meanEtaSeconds, double singleRunMs) {
    }

    record Report(String name, String evidence, String matrixSha256, int drivers, int rides, Summary sequential,
            Summary hungarian, boolean comparedSameRides, Double meanEtaReductionPercent) {
    }
}
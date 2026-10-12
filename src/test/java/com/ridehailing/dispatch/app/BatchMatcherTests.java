package com.ridehailing.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.ridehailing.geography.RoutingProvider.DurationMatrix;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class BatchMatcherTests {

    private final BatchMatcher matcher = new BatchMatcher();

    @Test
    void jointAssignmentImprovesTheSameMatrixWithoutChangingItsMatchCount() {
        DurationMatrix matrix = matrix(new double[][] {{1, 2}, {2, 100}});

        var sequential = matcher.sequential(matrix);
        var batch = matcher.solve(matrix);

        assertThat(sequential.totalEtaSeconds()).isEqualTo(101);
        assertThat(batch.totalEtaSeconds()).isEqualTo(4);
        assertThat(batch.matches()).containsExactly(new BatchMatcher.Match(1, 0, 2), new BatchMatcher.Match(0, 1, 2));
        assertThat(batch.unmatchedRides()).isZero();
    }

    @Test
    void maximumMatchCountComesBeforeMinimizingPickupTime() {
        DurationMatrix matrix = matrix(new double[][] {{0.5, 1}, {-1, 1000}});
        var batch = matcher.solve(matrix);
        assertThat(batch.matches()).containsExactly(new BatchMatcher.Match(0, 0, 0.5), new BatchMatcher.Match(1, 1, 1000));
        assertThat(batch.totalEtaSeconds()).isEqualTo(1000.5);
        DurationMatrix scarce = matrix(new double[][] {{1, 1.1}, {3, -1}});
        assertThat(matcher.sequential(scarce).matches()).hasSize(1);
        assertThat(matcher.solve(scarce).matches()).hasSize(2);
    }

    @Test
    void moreRidesThanDriversDoesNotInventSupplyOrReuseADriver() {
        var batch = matcher.solve(matrix(new double[][] {{4, 1, 8}, {2, 5, 9}}));
        assertThat(batch.matches()).containsExactly(new BatchMatcher.Match(1, 0, 2), new BatchMatcher.Match(0, 1, 1));
        assertThat(batch.unmatchedRides()).isEqualTo(1);
        assertThat(batch.totalEtaSeconds()).isEqualTo(3);
    }

    @Test
    void moreDriversThanRidesUsesOnlyTheBestReachableSupply() {
        var batch = matcher.solve(matrix(new double[][] {{8, -1}, {2.25, 4}, {-1, 1.75}}));
        assertThat(batch.matches()).containsExactly(new BatchMatcher.Match(1, 0, 2.25), new BatchMatcher.Match(2, 1, 1.75));
        assertThat(batch.meanEtaSeconds()).hasValue(2);
    }

    @Test
    void unreachablePairsNeverBecomeAssignmentsAndEmptyMeansNoMeasuredMean() {
        var unreachable = matcher.solve(matrix(new double[][] {{-1, -1}, {-1, -1}}));
        assertThat(unreachable.matches()).isEmpty();
        assertThat(unreachable.unmatchedRides()).isEqualTo(2);
        assertThat(unreachable.meanEtaSeconds()).isEmpty();
        var partial = matcher.solve(matrix(new double[][] {{-1, 3}, {-1, 2}}));
        assertThat(partial.matches()).containsExactly(new BatchMatcher.Match(1, 1, 2));
        assertThat(partial.unmatchedRides()).isEqualTo(1);
    }

    @Test
    void solverBoundsAreExplicitAndInputAndOutputAreImmutable() {
        DurationMatrix oversized = matrix(new double[BatchMatcher.MAX_SIZE + 1][1]);
        assertThatThrownBy(() -> matcher.solve(oversized)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> matcher.solve(matrix(new double[1][BatchMatcher.MAX_SIZE + 1])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> matcher.solve(matrix(new double[][] {{(double) Integer.MAX_VALUE + 1}})))
                .isInstanceOf(IllegalArgumentException.class);
        DurationMatrix original = matrix(new double[][] {{0, 1}, {1, 0}});
        var plan = matcher.solve(original);
        assertThat(original.durationsS().getFirst().getFirst()).hasValue(0);
        assertThatThrownBy(() -> plan.matches().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void generatedSmallProblemsMatchAnIndependentExhaustiveOracle() {
        Random random = new Random(321_907);
        for (int sample = 0; sample < 200; sample++) {
            double[][] costs = new double[1 + random.nextInt(5)][1 + random.nextInt(5)];
            for (double[] row : costs) {
                for (int column = 0; column < row.length; column++) {
                    row[column] = random.nextInt(4) == 0 ? -1 : random.nextInt(1000) / 4.0;
                }
            }
            DurationMatrix matrix = matrix(costs);
            var solved = matcher.solve(matrix);
            Optimum expected = exhaustive(costs, 0, new boolean[costs.length]);
            assertThat(solved.matches()).as("sample %d", sample).hasSize(expected.matched());
            assertThat(solved.totalEtaSeconds()).as("sample %d", sample).isCloseTo(expected.cost(), within(1e-8));
            assertThat(solved.matches()).extracting(BatchMatcher.Match::driver).doesNotHaveDuplicates();
            assertThat(solved.matches()).extracting(BatchMatcher.Match::ride).doesNotHaveDuplicates();
            assertThat(matcher.solve(matrix)).as("repeatability of sample %d", sample).isEqualTo(solved);
        }
    }

    @Test
    void theLargestSupportedMatrixKeepsEveryUniqueZeroCostMatch() {
        double[][] costs = new double[BatchMatcher.MAX_SIZE][BatchMatcher.MAX_SIZE];
        for (int driver = 0; driver < costs.length; driver++) {
            for (int ride = 0; ride < costs[driver].length; ride++) {
                costs[driver][ride] = driver == ride ? 0 : 1000;
            }
        }
        var solved = matcher.solve(matrix(costs));
        assertThat(solved.matches()).hasSize(BatchMatcher.MAX_SIZE);
        assertThat(solved.matches()).allSatisfy(match -> assertThat(match.driver()).isEqualTo(match.ride()));
        assertThat(solved.totalEtaSeconds()).isZero();
    }

        @Test
        void experimentReportsTheInputDigestAndDoesNotCompareMeansAcrossDifferentMatchedRides(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("matrix.json");
        Path output = directory.resolve("nested/report.json");
        Files.writeString(input, """
            {"name":"scarce-supply","durations_s":[[1,1.1],[3,null]]}
            """);

        BatchMatchingExperiment.main(new String[] {input.toString(), output.toString()});

        var report = JsonMapper.builder().build().readTree(Files.readString(output));
        assertThat(report.get("evidence").asString()).isEqualTo("OFFLINE_MATRIX_COMPARISON_NOT_ADOPTION");
        assertThat(report.get("matrix_sha256").asString()).isEqualTo(HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input))));
        assertThat(report.get("sequential").get("matches").size()).isEqualTo(1);
        assertThat(report.get("hungarian").get("matches").size()).isEqualTo(2);
        assertThat(report.get("compared_same_rides").asBoolean()).isFalse();
        assertThat(report.get("mean_eta_reduction_percent").isNull()).isTrue();
        assertThat(report.get("hungarian").get("match_rate").asDouble()).isEqualTo(1);
        }

        @Test
        void experimentRejectsBadMatricesOversizedFilesAndOverwritingItsInput(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("matrix.json");
        Path output = directory.resolve("report.json");
        for (String matrix : List.of("[]", "[[]]", "[[1],[2,3]]", "[[-1]]", "[[2147483648]]")) {
            Files.writeString(input, "{\"name\":\"invalid\",\"durations_s\":" + matrix + "}");
            assertThatThrownBy(() -> BatchMatchingExperiment.main(new String[] {input.toString(), output.toString()}))
                .isInstanceOf(IllegalArgumentException.class);
            assertThat(Files.exists(output)).isFalse();
        }
        Files.writeString(input, " ".repeat(2 * 1024 * 1024 + 1));
        assertThatThrownBy(() -> BatchMatchingExperiment.main(new String[] {input.toString(), output.toString()}))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2 MiB");
        assertThatThrownBy(() -> BatchMatchingExperiment.main(new String[] {input.toString(), input.toString()}))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("overwrite");
        assertThat(Files.size(input)).isEqualTo(2 * 1024 * 1024 + 1);
        }

        private static Optimum exhaustive(double[][] costs, int ride, boolean[] used) {
        if (ride == costs[0].length) {
            return new Optimum(0, 0);
        }
        Optimum best = exhaustive(costs, ride + 1, used);
        for (int driver = 0; driver < costs.length; driver++) {
            if (used[driver] || costs[driver][ride] < 0) {
                continue;
            }
            used[driver] = true;
            Optimum tail = exhaustive(costs, ride + 1, used);
            used[driver] = false;
            Optimum candidate = new Optimum(tail.matched() + 1, tail.cost() + costs[driver][ride]);
            if (candidate.matched() > best.matched() || (candidate.matched() == best.matched() && candidate.cost() < best.cost())) {
                best = candidate;
            }
        }
        return best;
    }

    private record Optimum(int matched, double cost) {
    }

    static DurationMatrix matrix(double[][] costs) {
        List<List<OptionalDouble>> rows = new ArrayList<>();
        for (double[] driver : costs) {
            List<OptionalDouble> row = new ArrayList<>();
            for (double value : driver) {
                row.add(value < 0 ? OptionalDouble.empty() : OptionalDouble.of(value));
            }
            rows.add(row);
        }
        return new DurationMatrix(rows);
    }
}
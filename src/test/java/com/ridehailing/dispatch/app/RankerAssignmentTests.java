package com.ridehailing.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.geography.RankingExperiment;
import com.ridehailing.geography.RankingWeights;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RankerAssignmentTests {

    private static final RankingWeights WEIGHTS = new RankingWeights(2, 70, 3, 40);

    @Test
    void noExperimentPreservesTheConfiguredRankerAndPolicyVersion() {
        var assignment = RankerAssignment.choose(new UUID(1, 2), "blr", "MINI", "nearest", 7, WEIGHTS, null);
        assertThat(assignment).isEqualTo(new RankerAssignment("nearest", 7, WEIGHTS, null));
    }

    @Test
    void zeroAndFullPercentagesSelectOnlyTheirIntendedStrategy() {
        for (int sequence = 0; sequence < 1000; sequence++) {
            UUID ride = new UUID(0, sequence);
            assertThat(choose(ride, 0).strategy()).isEqualTo("nearest");
            assertThat(choose(ride, 100).strategy()).isEqualTo("eta");
            assertThat(choose(ride, 100).experiment().bucket()).isBetween(0, 9999);
        }
    }

    @Test
    void assignmentsRepeatAndSequentialRideIdsAreNotUsedAsPercentileBuckets() {
        int treated = 0;
        boolean thresholdReached = false;
        for (int sequence = 0; sequence < 100_000; sequence++) {
            UUID ride = new UUID(0, sequence);
            var assignment = choose(ride, 30);
            assertThat(assignment.strategy()).isEqualTo(assignment.experiment().bucket() < 3000 ? "eta" : "nearest");
            if (assignment.strategy().equals("eta")) {
                treated++;
            }
            if (assignment.experiment().bucket() == 3000) {
                thresholdReached = true;
                assertThat(assignment.strategy()).isEqualTo("nearest");
            }
            if (sequence < 100) {
                assertThat(choose(ride, 30)).isEqualTo(assignment);
            }
        }
        assertThat(treated).isBetween(28_000, 32_000);
        assertThat(thresholdReached).isTrue();
    }

    @Test
    void changingAllocationKeepsBucketsWhileCityCategoryAndExperimentScopeThem() {
        UUID ride = new UUID(123, 456);
        var assignment = choose(ride, 30);
        assertThat(assignment.experiment().bucket()).isEqualTo(8526);
        assertThat(choose(ride, 50).experiment().bucket()).isEqualTo(assignment.experiment().bucket());
        assertThat(assignment.categoryVersion()).isEqualTo(7);
        assertThat(assignment.weights()).isEqualTo(WEIGHTS);
        assertThat(assignment.experiment().algorithm()).isEqualTo("sha256-ride-v1");
        assertThat(RankerAssignment.choose(ride, "mum", "MINI", "nearest", 7, WEIGHTS,
                new RankingExperiment("eta-trial", "eta", 30)).experiment().bucket()).isNotEqualTo(assignment.experiment().bucket());
        assertThat(RankerAssignment.choose(ride, "blr", "SEDAN", "nearest", 7, WEIGHTS,
                new RankingExperiment("eta-trial", "eta", 30)).experiment().bucket()).isNotEqualTo(assignment.experiment().bucket());
        assertThat(RankerAssignment.choose(ride, "blr", "MINI", "nearest", 7, WEIGHTS,
                new RankingExperiment("another-trial", "eta", 30)).experiment().bucket()).isNotEqualTo(assignment.experiment().bucket());
    }

    @Test
    void invalidExperimentConfigurationIsRejected() {
        assertThatThrownBy(() -> new RankingExperiment(null, "eta", 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RankingExperiment("trial\nother", "eta", 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RankingExperiment("TRIAL", "eta", 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RankingExperiment("a".repeat(65), "eta", 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RankingExperiment("trial", null, 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RankingExperiment("trial", "missing", 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RankingExperiment("trial", "eta", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RankingExperiment("trial", "eta", 101)).isInstanceOf(IllegalArgumentException.class);
    }

    private static RankerAssignment choose(UUID ride, int percentage) {
        return RankerAssignment.choose(ride, "blr", "MINI", "nearest", 7, WEIGHTS,
                new RankingExperiment("eta-trial", "eta", percentage));
    }
}
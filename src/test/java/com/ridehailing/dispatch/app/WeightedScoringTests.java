package com.ridehailing.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.ridehailing.geography.RankingWeights;
import com.ridehailing.shared.GeoPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WeightedScoringTests {

    private static final GeoPoint POSITION = new GeoPoint(0, 0);
    private static final GeoPoint NORTH = new GeoPoint(0.01, 0);

    @Test
    void theScoreHasIndependentlyInspectableComponents() {
        var score = WeightedScoring.score(new RankingWeights(2, 100, 3, 40), 30.25, 2, 3, 5,
                7, POSITION, NORTH, 180.0, 5.0);

        assertThat(score.acceptanceRate()).isEqualTo(0.5);
        assertThat(score.etaCost()).isEqualTo(60.5);
        assertThat(score.acceptanceCost()).isEqualTo(50);
        assertThat(score.idleCredit()).isEqualTo(21);
        assertThat(score.headingCost()).isEqualTo(40);
        assertThat(score.total()).isEqualTo(129.5);
    }

    @Test
    void aNewDriverUsesThePriorAndLargeCountersDoNotOverflow() {
        var fresh = score(0, 0, 0, 0, null, null);
        var experienced = score(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 0, null, null);

        assertThat(fresh.acceptanceRate()).isEqualTo(0.8);
        assertThat(experienced.acceptanceRate()).isCloseTo(1.0 / 3, within(1e-12));
        assertThat(experienced.total()).isFinite();
    }

    @ParameterizedTest
    @CsvSource({"-5,0", "0,0", "12.5,12.5", "30,30", "300,30"})
    void idleCreditIsClamped(double idle, double expected) {
        assertThat(score(0, 0, 0, idle, null, null).idleMinutes()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "90,0.5", "180,1", "270,0.5", "359,0.00007615242180436521"})
    void headingsUseCircularAngularDistance(double heading, double penalty) {
        assertThat(score(0, 0, 0, 0, heading, 5.0).headingPenalty()).isCloseTo(penalty, within(1e-12));
    }

    @Test
    void missingStationaryOrInvalidMotionIsNeutralAndThePickupNeedsNoHeading() {
        assertThat(score(0, 0, 0, 0, null, 5.0).headingPenalty()).isEqualTo(0.5);
        assertThat(score(0, 0, 0, 0, 0.0, null).headingPenalty()).isEqualTo(0.5);
        assertThat(score(0, 0, 0, 0, 0.0, 0.999).headingPenalty()).isEqualTo(0.5);
        assertThat(score(0, 0, 0, 0, 0.0, 1.0).headingPenalty()).isZero();
        assertThat(score(0, 0, 0, 0, Double.NaN, 5.0).headingPenalty()).isEqualTo(0.5);
        assertThat(score(0, 0, 0, 0, 360.0, 5.0).headingPenalty()).isEqualTo(0.5);
        assertThat(WeightedScoring.score(RankingWeights.DEFAULT, 0, 0, 0, 0, 0, POSITION, POSITION, null, null)
                .headingPenalty()).isZero();
    }

    @Test
    void invalidInputsAndWeightsAreRejected() {
        assertThatThrownBy(() -> score(-1, 0, 0, 0, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> score(0, 0, 0, Double.NaN, null, null)).isInstanceOf(IllegalArgumentException.class);
        for (double invalid : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> new RankingWeights(invalid, 60, 2, 30)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RankingWeights(1, invalid, 2, 30)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new RankingWeights(0, 60, 2, 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RankingWeights(1, 60, 61, 30)).isInstanceOf(IllegalArgumentException.class);
        for (double invalidEta : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY, (double) Integer.MAX_VALUE + 1}) {
            assertThatThrownBy(() -> WeightedScoring.score(RankingWeights.DEFAULT, invalidEta, 0, 0, 0, 0,
                POSITION, NORTH, null, null)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(WeightedScoring.score(new RankingWeights(1000, 0, 0, 0), Integer.MAX_VALUE, 0, 0, 0, 0,
            POSITION, NORTH, null, null).total()).isEqualTo(1000.0 * Integer.MAX_VALUE);
    }

    private static WeightedScoring.Score score(long accepted, long declined, long expired, double idle, Double heading, Double speed) {
        return WeightedScoring.score(RankingWeights.DEFAULT, 100, accepted, declined, expired, idle, POSITION, NORTH, heading, speed);
    }
}
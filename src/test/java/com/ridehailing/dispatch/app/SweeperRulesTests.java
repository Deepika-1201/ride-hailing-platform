package com.ridehailing.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** LLD §8.9: when a driver counts as silent, and when the safety valve holds. */
class SweeperRulesTests {

    private static final Instant T = Instant.parse("2026-10-02T08:00:00Z");

    @Test
    void silenceIsMeasuredFromTheLastUpdateButNoEarlierThanGoingOnline() {
        assertThat(Sweeper.lastSeen(T.plusSeconds(60), T, T.minusSeconds(600))).isEqualTo(T.plusSeconds(60));
        assertThat(Sweeper.lastSeen(T.minusSeconds(60), T, T.minusSeconds(600))).as("a previous session's update")
                .isEqualTo(T);
    }

    @Test
    void aDriverNotHeardFromCountsAsSeenAtTheLaterOfTheEpochAndGoingOnline() {
        assertThat(Sweeper.lastSeen(null, T, T.minusSeconds(600))).isEqualTo(T);
        assertThat(Sweeper.lastSeen(null, T, T.plusSeconds(600))).isEqualTo(T.plusSeconds(600));
    }

    @Test
    void anEpochAfterTheIdleCutoffIsTooYoung() {
        assertThat(Sweeper.epochTooYoung(T.plusMillis(1), T)).isTrue();
        assertThat(Sweeper.epochTooYoung(T, T)).isFalse();
        assertThat(Sweeper.epochTooYoung(T.minusMillis(1), T)).isFalse();
    }

    @Test
    void massSilenceIsMoreThanFiveDriversAndMoreThanATenth() {
        assertThat(Sweeper.massSilence(5, 5)).isFalse();
        assertThat(Sweeper.massSilence(6, 6)).isTrue();
        assertThat(Sweeper.massSilence(6, 59)).isTrue();
        assertThat(Sweeper.massSilence(6, 60)).isFalse();
        assertThat(Sweeper.massSilence(7, 60)).isTrue();
        assertThat(Sweeper.massSilence(20, 200)).isFalse();
        assertThat(Sweeper.massSilence(21, 200)).isTrue();
    }
}

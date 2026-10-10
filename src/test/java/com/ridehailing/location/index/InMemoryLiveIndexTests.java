package com.ridehailing.location.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndexContract;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class InMemoryLiveIndexTests extends LiveIndexContract {

    @Override
    protected LiveIndex newIndex(Clock clock, Duration freshness, Duration tombstoneTtl) {
        return new InMemoryLiveIndex(clock, freshness, tombstoneTtl, LiveIndex.Quality.DEFAULT);
    }

    @Test
    void theEpochIsWhenTheIndexWasCreatedWhetherOrNotTheCityBegan() {
        assertThat(index.epoch(city)).isEqualTo(clock.instant());
        clock.advance(Duration.ofSeconds(5));
        index.beginEpoch(city);

        assertThat(index.epoch(city)).isEqualTo(clock.instant().minusSeconds(5));
    }
}

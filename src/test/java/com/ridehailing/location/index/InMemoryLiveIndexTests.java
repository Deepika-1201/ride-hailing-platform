package com.ridehailing.location.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.Candidate;
import com.ridehailing.location.LiveIndexContract;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryLiveIndexTests extends LiveIndexContract {

    @Override
    protected LiveIndex newIndex(Clock clock, Duration freshness, Duration tombstoneTtl) {
        return new InMemoryLiveIndex(clock, freshness, tombstoneTtl);
    }

    @Test
    void theEpochIsWhenTheIndexWasCreated() {
        assertThat(index.epoch(city)).isEqualTo(clock.instant());
    }

    @Test
    void equallyDistantDriversComeInDriverIdOrder() {
        List<UUID> drivers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            drivers.add(at(north(100)));
        }

        assertThat(index.nearby(city, MINI, HERE, 1_000, 6)).extracting(Candidate::driverId)
                .containsExactlyElementsOf(drivers.stream().sorted().toList());
    }
}

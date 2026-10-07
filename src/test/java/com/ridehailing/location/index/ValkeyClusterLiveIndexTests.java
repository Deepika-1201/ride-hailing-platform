package com.ridehailing.location.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.Candidate;
import com.ridehailing.location.LiveIndexContract;
import com.ridehailing.platform.Valkey;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.Valkeys;
import io.lettuce.core.cluster.SlotHash;
import io.lettuce.core.cluster.models.partitions.ClusterPartitionParser;
import io.lettuce.core.cluster.models.partitions.Partitions;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The contract on a 3-shard Valkey cluster (LLD §17.1): each call's keys share the city's hash tag, so every script
 * runs on the shard that owns the city.
 */
class ValkeyClusterLiveIndexTests extends LiveIndexContract {

    private final Valkey valkey = Valkeys.cluster();

    @Override
    protected LiveIndex newIndex(Clock clock, Duration freshness, Duration tombstoneTtl) {
        return new ValkeyLiveIndex(valkey, clock, freshness, tombstoneTtl);
    }

    @Test
    void citiesLiveOnEveryShardAndEachWorksOnItsOwn() {
        Partitions partitions = ClusterPartitionParser.parse(valkey.await("test", Valkeys.TIMEOUTS.other(),
                valkey.commands().clusterNodes()));
        Set<String> shards = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            city = TestCities.newId();
            shards.add(partitions.getPartitionBySlot(SlotHash.getSlot("{" + city + "}:drivers")).getNodeId());

            UUID driver = at(north(100));

            assertThat(index.nearby(city, MINI, HERE, 1_000, 5)).extracting(Candidate::driverId)
                    .containsExactly(driver);
        }

        assertThat(shards).hasSize(3);
    }
}

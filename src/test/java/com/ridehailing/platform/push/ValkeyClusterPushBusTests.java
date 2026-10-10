package com.ridehailing.platform.push;

import com.ridehailing.platform.valkey.LettuceValkey;
import com.ridehailing.support.Valkeys;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** LLD §14.7: the contract on a 3-shard cluster, with sharded pub/sub. */
class ValkeyClusterPushBusTests extends ValkeyPushBusContract {

    @Override
    protected LettuceValkey valkey() {
        return Valkeys.cluster();
    }

    @Test
    void channelsOnEveryShardDeliver() {
        Inbox inbox = new Inbox();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            String spread = "test:" + UUID.randomUUID();
            bus.subscribe(spread, inbox);
            bus.publish(spread, new Note("n" + i, null));
            expected.add(note("n" + i));
        }

        inbox.awaitInAnyOrder(expected);
    }
}

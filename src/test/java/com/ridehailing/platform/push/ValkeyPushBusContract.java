package com.ridehailing.platform.push;

import com.ridehailing.platform.PushBus;
import com.ridehailing.platform.PushBusContract;
import com.ridehailing.platform.valkey.LettuceValkey;
import org.junit.jupiter.api.Test;

/** LLD §14.7: through Valkey, a push from one node reaches the sessions another node holds. */
abstract class ValkeyPushBusContract extends PushBusContract {

    protected abstract LettuceValkey valkey();

    @Override
    protected PushBus newBus() {
        return new ValkeyPushBus(valkey(), JSON);
    }

    @Test
    void aPushFromOneNodeReachesTheSubscriberOnAnother() {
        Inbox inbox = new Inbox();
        bus.subscribe(channel, inbox);

        try (ValkeyPushBus otherNode = new ValkeyPushBus(valkey(), JSON)) {
            otherNode.publish(channel, new Note("across", null));

            inbox.awaitExactly(note("across"));
        }
    }
}

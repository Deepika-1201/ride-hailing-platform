package com.ridehailing.platform.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.platform.Valkey;
import com.ridehailing.platform.valkey.LettuceValkey;
import com.ridehailing.support.Valkeys;
import io.lettuce.core.RedisException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** LLD §14.7: the contract on a single Valkey node, and what a bus does while Valkey doesn't answer. */
class ValkeyPushBusTests extends ValkeyPushBusContract {

    private static final Duration SHORT = Duration.ofMillis(200);

    @Override
    protected LettuceValkey valkey() {
        return Valkeys.standalone();
    }

    @Test
    void withoutValkeyASubscriptionFailsAndAPushIsLostWithoutWaiting() {
        try (LettuceValkey unreachable = Valkeys.newStandalone(new Valkey.Timeouts(SHORT, SHORT, SHORT, SHORT));
                ValkeyPushBus cut = new ValkeyPushBus(unreachable, JSON)) {
            Inbox inbox = new Inbox();

            Valkeys.whilePaused(() -> {
                long started = System.nanoTime();
                cut.publish(channel, new Note("lost", null));
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(SHORT);
                assertThatThrownBy(() -> cut.subscribe(channel, inbox)).isInstanceOf(RedisException.class);
            });

            cut.subscribe(channel, inbox);
            cut.publish(channel, new Note("back", null));
            inbox.awaitExactly(note("back"));
        }
    }
}

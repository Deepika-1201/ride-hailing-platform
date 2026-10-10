package com.ridehailing.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ridehailing.platform.PushBus.Subscription;
import com.ridehailing.support.Eventually;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** LLD §14.7: what a push bus does, in memory and through Valkey. */
public abstract class PushBusContract {

    /** As {@code application.yml} configures it: snake_case, null fields omitted. */
    protected static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();
    protected static final Duration WAIT = Duration.ofSeconds(5);

    protected final String channel = "test:" + UUID.randomUUID();
    protected PushBus bus;

    protected abstract PushBus newBus();

    @BeforeEach
    void startBus() {
        bus = newBus();
    }

    @AfterEach
    void closeBus() throws Exception {
        if (bus instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    void aSubscriberGetsItsChannelsMessagesAsJsonInOrder() {
        Inbox inbox = new Inbox();
        bus.subscribe(channel, inbox);

        bus.publish("test:" + UUID.randomUUID(), new Note("elsewhere", null));
        bus.publish(channel, new Note("first", null));
        bus.publish(channel, new Note("second", 2));

        inbox.awaitExactly("{\"text\":\"first\"}", "{\"text\":\"second\",\"seq_no\":2}");
    }

    @Test
    void receiversOfAChannelShareItAndLeaveOneByOne() {
        Inbox leaving = new Inbox();
        Inbox staying = new Inbox();
        Subscription left = bus.subscribe(channel, leaving);
        bus.subscribe(channel, staying);
        bus.publish(channel, new Note("both", null));
        leaving.awaitExactly(note("both"));

        left.close();
        left.close();
        bus.publish(channel, new Note("one", null));

        staying.awaitExactly(note("both"), note("one"));
        assertThat(leaving.messages()).containsExactly(note("both"));
    }

    @Test
    void theSameReceiverTwiceGetsEachMessageTwiceUntilOneSubscriptionCloses() {
        Inbox inbox = new Inbox();
        Subscription once = bus.subscribe(channel, inbox);
        bus.subscribe(channel, inbox);
        bus.publish(channel, new Note("twice", null));
        inbox.awaitExactly(note("twice"), note("twice"));

        once.close();
        once.close();
        bus.publish(channel, new Note("once", null));

        inbox.awaitExactly(note("twice"), note("twice"), note("once"));
    }

    @Test
    void aChannelItsLastReceiverLeftCanBeJoinedAgain() {
        Inbox gone = new Inbox();
        bus.subscribe(channel, gone).close();
        Inbox back = new Inbox();
        bus.subscribe(channel, back);

        bus.publish(channel, new Note("again", null));

        back.awaitExactly(note("again"));
        assertThat(gone.messages()).isEmpty();
    }

    @Test
    void aFailingReceiverDoesntStopTheOthers() {
        bus.subscribe(channel, message -> {
            throw new IllegalStateException("A broken receiver");
        });
        Inbox inbox = new Inbox();
        bus.subscribe(channel, inbox);

        bus.publish(channel, new Note("still", null));

        inbox.awaitExactly(note("still"));
    }

    @Test
    void pushingToAChannelNobodyHoldsIsLostQuietly() {
        assertThatCode(() -> bus.publish("test:" + UUID.randomUUID(), new Note("lost", null)))
                .doesNotThrowAnyException();
    }

    protected static String note(String text) {
        return "{\"text\":\"" + text + "\"}";
    }

    public record Note(String text, Integer seqNo) {
    }

    /** What a receiver got, in order. */
    public static final class Inbox implements Consumer<String> {

        private final List<String> messages = new CopyOnWriteArrayList<>();

        @Override
        public void accept(String message) {
            messages.add(message);
        }

        public List<String> messages() {
            return List.copyOf(messages);
        }

        public void awaitExactly(String... expected) {
            Eventually.within(WAIT, () -> assertThat(messages).containsExactly(expected));
        }

        public void awaitInAnyOrder(List<String> expected) {
            Eventually.within(WAIT, () -> assertThat(messages).containsExactlyInAnyOrderElementsOf(expected));
        }
    }
}

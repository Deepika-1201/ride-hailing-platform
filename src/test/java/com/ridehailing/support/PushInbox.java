package com.ridehailing.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.PushBus;
import com.ridehailing.platform.PushBus.Subscription;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;

/** What the push bus sent to one channel since the inbox opened (LLD §14.7); reading checks each against its schema. */
public final class PushInbox implements AutoCloseable {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final List<String> received = new CopyOnWriteArrayList<>();
    private final Subscription subscription;

    private PushInbox(PushBus bus, String channel) {
        subscription = bus.subscribe(channel, received::add);
    }

    public static PushInbox open(PushBus bus, String channel) {
        return new PushInbox(bus, channel);
    }

    public List<JsonNode> messages() {
        return received.stream().map(WebSocketContract::assertConforms).toList();
    }

    /** Once exactly {@code count} messages have arrived. */
    public List<JsonNode> await(int count) {
        Eventually.within(WAIT, () -> assertThat(received).hasSize(count));
        return messages();
    }

    /** {@code type}, and the status or reason where the message has one, of each message in order. */
    public List<String> summary() {
        return messages().stream().map(message -> message.get("type").asString()
                + (message.has("status") ? " " + message.get("status").asString() : "")
                + (message.has("reason") ? " " + message.get("reason").asString() : "")).toList();
    }

    @Override
    public void close() {
        subscription.close();
    }
}

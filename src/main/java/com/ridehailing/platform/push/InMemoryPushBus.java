package com.ridehailing.platform.push;

import com.ridehailing.platform.PushBus;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Pushes within this process, for the memory store (LLD §1.3): a single node holds every session. */
@Component
@ConditionalOnProperty(name = "ride.location.store", havingValue = "memory", matchIfMissing = true)
class InMemoryPushBus implements PushBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPushBus.class);

    private final Receivers receivers = new Receivers();
    private final JsonMapper json;

    InMemoryPushBus(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void publish(String channel, Object message) {
        try {
            receivers.deliver(channel, json.writeValueAsString(message));
        } catch (RuntimeException e) {
            log.warn("Pushing to {} failed", channel, e);
        }
    }

    @Override
    public Subscription subscribe(String channel, Consumer<String> receiver) {
        receivers.add(channel, receiver, () -> {
        });
        return receivers.subscription(channel, receiver, () -> {
        });
    }
}

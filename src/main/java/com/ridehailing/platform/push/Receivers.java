package com.ridehailing.platform.push;

import com.ridehailing.platform.PushBus.Subscription;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The receivers of each channel this node holds (LLD §14.7). The first receiver in subscribes the node and the last
 * one out unsubscribes it; those actions run under the registry's lock, so they reach Valkey in the order decided.
 */
final class Receivers {

    private static final Logger log = LoggerFactory.getLogger(Receivers.class);

    private final ConcurrentHashMap<String, List<Consumer<String>>> channels = new ConcurrentHashMap<>();

    synchronized void add(String channel, Consumer<String> receiver, Runnable ifFirst) {
        List<Consumer<String>> receivers = channels.computeIfAbsent(channel, any -> new CopyOnWriteArrayList<>());
        receivers.add(receiver);
        if (receivers.size() == 1) {
            ifFirst.run();
        }
    }

    synchronized void remove(String channel, Consumer<String> receiver, Runnable ifLast) {
        List<Consumer<String>> receivers = channels.get(channel);
        if (receivers != null && receivers.remove(receiver) && receivers.isEmpty()) {
            channels.remove(channel);
            ifLast.run();
        }
    }

    /** Removes the receiver on its first close only, as the same receiver may be in twice. */
    Subscription subscription(String channel, Consumer<String> receiver, Runnable ifLast) {
        AtomicBoolean open = new AtomicBoolean(true);
        return () -> {
            if (open.compareAndSet(true, false)) {
                remove(channel, receiver, ifLast);
            }
        };
    }

    /** A receiver that fails doesn't keep the message from the others. */
    void deliver(String channel, String message) {
        for (Consumer<String> receiver : channels.getOrDefault(channel, List.of())) {
            try {
                receiver.accept(message);
            } catch (RuntimeException e) {
                log.warn("A receiver of {} failed", channel, e);
            }
        }
    }
}

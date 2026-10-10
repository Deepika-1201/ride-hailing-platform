package com.ridehailing.platform;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Pushes to WebSocket sessions over subject channels (LLD §14.3, §14.7): in the process with the memory store,
 * otherwise through Valkey pub/sub, sharded in a cluster. Best effort, as ADR-006 allows: a message is lost when no
 * node holds the channel or Valkey is unreachable, and clients resync over HTTPS when they reconnect.
 */
public interface PushBus {

    /**
     * Sends the message as JSON now, without waiting for Valkey, and never throws. Publishers call it once their
     * transaction has committed ({@link Transactions#afterCommit}), so a rollback pushes nothing.
     */
    void publish(String channel, Object message);

    /**
     * Delivers the channel's messages to the receiver until the subscription closes. The receiver runs on a thread it
     * mustn't block. A node keeps one subscription per channel, shared by its receivers.
     */
    Subscription subscribe(String channel, Consumer<String> receiver);

    static String driverChannel(UUID driverId) {
        return "drv:" + driverId;
    }

    static String riderChannel(UUID riderId) {
        return "rdr:" + riderId;
    }

    static String rideChannel(UUID rideId) {
        return "ride:" + rideId;
    }

    /** Closing it more than once is harmless. */
    interface Subscription extends AutoCloseable {

        @Override
        void close();
    }
}

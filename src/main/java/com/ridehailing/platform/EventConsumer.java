package com.ridehailing.platform;

import java.util.Set;

/**
 * Handles events of the given types (LLD §5.3). Each event reaches a consumer at least once; the inbox makes the
 * effect happen once. {@code handle} runs inside the consumer's transaction and does database work only.
 */
public interface EventConsumer {

    /** Unique and stable, such as {@code payment.charges}: it keys the inbox. */
    String name();

    Set<String> eventTypes();

    void handle(EventEnvelope event);
}

package com.ridehailing.platform;

import java.util.List;
import java.util.UUID;

/** The events still in the outbox, for reading back (LLD §2.2): a ride's timeline (§13.5). */
public interface EventLog {

    /** The events with the partition key, such as a ride's ride, offer and payment events, oldest first. */
    List<EventEnvelope> byPartitionKey(UUID key);
}

package com.ridehailing.platform;

import java.util.UUID;

/** Events a consumer failed to handle after its retries, set aside so the stream moves on (LLD §5.3). */
public interface FailedDeliveries {

    /**
     * Delivers the event to the consumer once more.
     *
     * @return true if it was handled now or had been already; false if it failed again
     * @throws java.util.NoSuchElementException if there is no unresolved failed delivery for the pair
     */
    boolean redrive(String consumer, UUID eventId);
}

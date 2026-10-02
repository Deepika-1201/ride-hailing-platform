package com.ridehailing.shared;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UUIDv7 identifiers (RFC 9562), created in the application so they exist before the insert (LLD §1.4). They are
 * monotonic per JVM: a 12-bit counter below the millisecond orders IDs created in the same millisecond (method 3), and
 * 62 random bits make them hard to guess.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    // Unix milliseconds << 12 | counter. Strictly increasing, so under a burst it may run slightly ahead of the clock.
    private static final AtomicLong LAST = new AtomicLong();

    private Ids() {
    }

    public static UUID newId() {
        long now = System.currentTimeMillis() << 12;
        long stamp = LAST.updateAndGet(last -> Math.max(now, last + 1));
        long mostSignificant = (stamp >>> 12) << 16 | 0x7000L | (stamp & 0xFFFL);
        long leastSignificant = 0x8000_0000_0000_0000L | (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL);
        return new UUID(mostSignificant, leastSignificant);
    }
}

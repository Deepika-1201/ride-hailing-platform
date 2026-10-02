package com.ridehailing.platform;

import java.time.Duration;
import java.util.OptionalLong;

/**
 * Named leases in the database (LLD §5.5). Each acquisition increments the lease's fencing token, so a write that
 * checks the token in the same statement is rejected once a newer holder exists.
 */
public interface Leases {

    /** Takes the lease if it is free or expired, returning the new fencing token; empty if another holder has it. */
    OptionalLong acquire(String name, String holder, Duration ttl);

    /** Extends the lease while {@code holder} still has it with this token; false if it was lost. */
    boolean renew(String name, String holder, long token, Duration ttl);

    /** Frees the lease, if {@code holder} still has it with this token. */
    void release(String name, String holder, long token);
}

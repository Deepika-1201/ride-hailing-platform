package com.ridehailing.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Empties the platform's tables, so each test starts from a known state on the shared database. */
public final class PlatformTables {

    private PlatformTables() {
    }

    public static void reset(JdbcClient jdbc) {
        jdbc.sql("""
                TRUNCATE platform.outbox, platform.inbox, platform.failed_deliveries, platform.timers,
                         platform.idempotency_keys, platform.leases
                """).update();
    }
}

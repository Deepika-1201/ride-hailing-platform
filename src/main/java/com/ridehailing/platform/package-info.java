/**
 * Infrastructure every module uses: roles, HTTP conventions, migrations, transactions, idempotency keys, the outbox
 * and its relay, timers, leases and recurring jobs.
 */
@ApplicationModule(displayName = "Platform", allowedDependencies = "shared")
package com.ridehailing.platform;

import org.springframework.modulith.ApplicationModule;

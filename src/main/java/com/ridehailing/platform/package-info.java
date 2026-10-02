/** Infrastructure every module uses: roles, HTTP conventions, migrations; later idempotency, outbox, timers and jobs. */
@ApplicationModule(displayName = "Platform", allowedDependencies = "shared")
package com.ridehailing.platform;

import org.springframework.modulith.ApplicationModule;

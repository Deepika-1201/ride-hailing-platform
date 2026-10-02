/** Driver availability, search tasks, offers and dispatch decisions (ADR-011). */
@ApplicationModule(
        displayName = "Dispatch",
        allowedDependencies = {"ride", "driver", "rating", "location", "geography", "audit", "platform", "shared"})
package com.ridehailing.dispatch;

import org.springframework.modulith.ApplicationModule;

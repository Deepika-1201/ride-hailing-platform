/** Operations views and commands composed from other modules' APIs; owns no tables. */
@ApplicationModule(
        displayName = "Operations",
        allowedDependencies = {"ride", "dispatch", "driver", "payment", "notification", "rating", "location", "audit",
                "platform", "shared"})
package com.ridehailing.operations;

import org.springframework.modulith.ApplicationModule;

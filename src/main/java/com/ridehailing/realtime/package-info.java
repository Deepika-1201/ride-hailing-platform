/** WebSocket sessions (LLD §14): tickets, pushes to riders, drivers and operations, and drivers' live locations. */
@ApplicationModule(displayName = "Realtime", allowedDependencies = {"identity", "dispatch", "ride", "location",
        "geography", "notification", "platform", "shared"})
package com.ridehailing.realtime;

import org.springframework.modulith.ApplicationModule;

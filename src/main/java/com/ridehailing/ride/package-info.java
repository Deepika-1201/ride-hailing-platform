/** Rides and their state machine; declares the participant interface that dispatch implements (ADR-019). */
@ApplicationModule(
        displayName = "Ride",
        allowedDependencies = {"pricing", "payment", "rider", "rating", "location", "audit", "platform", "shared"})
package com.ridehailing.ride;

import org.springframework.modulith.ApplicationModule;

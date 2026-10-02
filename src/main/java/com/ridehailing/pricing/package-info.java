/** Fare, fee and surge rules, and quotes (ADR-012). */
@ApplicationModule(displayName = "Pricing", allowedDependencies = {"geography", "location", "audit", "platform", "shared"})
package com.ridehailing.pricing;

import org.springframework.modulith.ApplicationModule;

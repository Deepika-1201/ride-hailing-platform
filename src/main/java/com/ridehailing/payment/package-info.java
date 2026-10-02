/** Charges, attempts, refunds, provider webhooks, rider dues and driver earnings (ADR-014). */
@ApplicationModule(displayName = "Payment", allowedDependencies = {"rider", "audit", "platform", "shared"})
package com.ridehailing.payment;

import org.springframework.modulith.ApplicationModule;

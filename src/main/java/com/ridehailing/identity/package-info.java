/** Users, roles, one-time codes and tokens (ADR-015). */
@ApplicationModule(displayName = "Identity", allowedDependencies = {"notification", "audit", "platform", "shared"})
package com.ridehailing.identity;

import org.springframework.modulith.ApplicationModule;

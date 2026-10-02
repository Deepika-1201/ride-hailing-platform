package com.ridehailing.platform.roles;

import com.ridehailing.platform.Role;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validates {@code ride.roles} at startup, so an empty or unknown role fails fast. */
@Validated
@ConfigurationProperties(prefix = "ride")
public record RoleProperties(@NotEmpty Set<Role> roles) {
}

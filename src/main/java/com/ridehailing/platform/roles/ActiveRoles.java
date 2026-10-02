package com.ridehailing.platform.roles;

import com.ridehailing.platform.Role;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/** Reads {@code ride.roles} straight from the environment, for conditions evaluated before any bean exists. */
final class ActiveRoles {

    static final String PROPERTY = "ride.roles";

    private ActiveRoles() {
    }

    static Set<Role> of(Environment environment) {
        return Binder.get(environment)
                .bind(PROPERTY, Bindable.setOf(Role.class))
                .map(Set::copyOf)
                .orElseGet(() -> EnumSet.allOf(Role.class));
    }
}

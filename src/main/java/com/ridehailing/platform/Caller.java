package com.ridehailing.platform;

import com.ridehailing.shared.Actor;
import com.ridehailing.shared.UserRole;
import java.util.Set;
import java.util.UUID;

/**
 * The signed-in user making a request, from their access token. Controllers take it as a parameter and pass it to
 * application services, which check ownership (LLD §12.4).
 */
public record Caller(UUID userId, Set<UserRole> roles) {

    public Caller {
        roles = Set.copyOf(roles);
    }

    public boolean has(UserRole role) {
        return roles.contains(role);
    }

    /** This user as the actor of an audited action, in one of their roles. */
    public Actor as(UserRole role) {
        return new Actor(Actor.Type.valueOf(role.name()), userId.toString());
    }
}

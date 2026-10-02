package com.ridehailing.shared;

import java.util.Objects;

/** Who did something: a user in one of their roles, or a system component such as the sweeper. */
public record Actor(Type type, String id) {

    public enum Type {
        RIDER,
        DRIVER,
        OPS,
        ADMIN,
        SYSTEM
    }

    public Actor {
        Objects.requireNonNull(type, "type");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("An actor needs an id");
        }
    }

    public static Actor system(String component) {
        return new Actor(Type.SYSTEM, component);
    }
}

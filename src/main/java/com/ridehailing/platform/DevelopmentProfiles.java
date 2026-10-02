package com.ridehailing.platform;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** The {@code local} and {@code test} profiles, the only ones allowed fixed codes and generated keys (LLD §12). */
public final class DevelopmentProfiles {

    private DevelopmentProfiles() {
    }

    public static boolean active(Environment environment) {
        return environment.acceptsProfiles(Profiles.of("local", "test"));
    }
}

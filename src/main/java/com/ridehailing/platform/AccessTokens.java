package com.ridehailing.platform;

import com.ridehailing.shared.UserRole;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/** Issues signed access tokens (LLD §12.2); the identity module decides whom to issue them to. */
public interface AccessTokens {

    AccessToken issue(UUID userId, Set<UserRole> roles);

    record AccessToken(String value, Duration expiresIn) {
    }
}

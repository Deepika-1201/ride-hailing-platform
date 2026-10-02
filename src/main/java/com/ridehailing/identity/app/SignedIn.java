package com.ridehailing.identity.app;

import com.ridehailing.shared.UserRole;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/** A successful sign-in or refresh: an access token, a refresh token, and who they are for. */
public record SignedIn(
        String accessToken,
        Duration accessExpiresIn,
        String refreshToken,
        Duration refreshExpiresIn,
        UUID userId,
        Set<UserRole> roles) {
}

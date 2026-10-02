package com.ridehailing.platform.security;

import com.ridehailing.platform.Caller;
import com.ridehailing.shared.UserRole;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The caller of the current request, from the access token Spring Security has already verified. */
public final class Callers {

    private static final Set<String> KNOWN_ROLES =
            Arrays.stream(UserRole.values()).map(UserRole::name).collect(Collectors.toUnmodifiableSet());

    private Callers() {
    }

    public static Optional<Caller> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        UUID userId;
        try {
            userId = UUID.fromString(token.getToken().getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
        List<String> roles = token.getToken().getClaimAsStringList(JwtAccessTokens.ROLES_CLAIM);
        Set<UserRole> granted = roles == null ? Set.of() : roles.stream()
                .filter(KNOWN_ROLES::contains)
                .map(UserRole::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        return Optional.of(new Caller(userId, granted));
    }
}

package com.ridehailing.identity.app;

import com.ridehailing.identity.db.RefreshTokens;
import com.ridehailing.identity.db.Users.User;
import com.ridehailing.platform.AccessTokens;
import com.ridehailing.platform.AccessTokens.AccessToken;
import com.ridehailing.shared.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Issues an access token and a refresh token; runs inside the caller's transaction (LLD §12.2). */
@Component
class TokenPairs {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final RefreshTokens refreshTokens;
    private final AccessTokens accessTokens;
    private final SessionProperties properties;

    TokenPairs(RefreshTokens refreshTokens, AccessTokens accessTokens, SessionProperties properties) {
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.properties = properties;
    }

    /** {@code parentId} is the token this pair replaces, or null at sign-in, which starts a new family. */
    SignedIn issue(User user, UUID familyId, UUID parentId) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String refreshToken = BASE64URL.encodeToString(secret);
        refreshTokens.insert(Ids.newId(), familyId, parentId, user.id(), hash(refreshToken), properties.refreshTtl());
        AccessToken access = accessTokens.issue(user.id(), user.roles());
        return new SignedIn(access.value(), access.expiresIn(), refreshToken, properties.refreshTtl(), user.id(),
                user.roles());
    }

    /** Only the SHA-256 of a refresh token is stored, so a database leak yields no usable tokens. */
    static byte[] hash(String refreshToken) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}

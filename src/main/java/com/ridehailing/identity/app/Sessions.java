package com.ridehailing.identity.app;

import com.ridehailing.audit.AuditEntry;
import com.ridehailing.audit.AuditLog;
import com.ridehailing.identity.db.RefreshTokens;
import com.ridehailing.identity.db.RefreshTokens.StoredToken;
import com.ridehailing.identity.db.Users;
import com.ridehailing.identity.db.Users.User;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Transactions;
import com.ridehailing.shared.Actor;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Refresh-token rotation with reuse detection, and logout (LLD §12.2). */
@Service
public class Sessions {

    private static final Logger log = LoggerFactory.getLogger(Sessions.class);

    private final RefreshTokens refreshTokens;
    private final Users users;
    private final TokenPairs tokens;
    private final AuditLog auditLog;
    private final Transactions transactions;
    private final SessionProperties properties;

    Sessions(RefreshTokens refreshTokens, Users users, TokenPairs tokens, AuditLog auditLog,
            Transactions transactions, SessionProperties properties) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.tokens = tokens;
        this.auditLog = auditLog;
        this.transactions = transactions;
        this.properties = properties;
    }

    public SignedIn refresh(String refreshToken) {
        return switch (transactions.execute(() -> rotate(refreshToken))) {
            case Outcome.Rotated rotated -> rotated.signedIn();
            case Outcome.Refused refused -> throw refused.error();
        };
    }

    /** Revokes the token's whole family; an unknown token is ignored, so logout can't be used to test tokens. */
    public void logout(String refreshToken) {
        transactions.run(() -> refreshTokens.familyOf(TokenPairs.hash(refreshToken))
                .ifPresent(refreshTokens::revokeFamily));
    }

    // Runs in one transaction that holds the token's row lock, so concurrent refreshes take turns.
    private Outcome rotate(String presented) {
        Optional<StoredToken> found = refreshTokens.findForUpdate(TokenPairs.hash(presented),
                properties.refreshReuseGrace());
        if (found.isEmpty() || found.get().dead()) {
            return new Outcome.Refused(tokenInvalid());
        }
        StoredToken token = found.get();
        User user = users.find(token.userId()).orElseThrow();
        if (!user.active()) {
            refreshTokens.revokeFamily(token.familyId());
            return new Outcome.Refused(Accounts.disabled());
        }
        if (!token.rotated()) {
            refreshTokens.markRotated(token.id());
            return new Outcome.Rotated(tokens.issue(user, token.familyId(), token.id()));
        }
        if (token.inGrace()) {
            // A retry whose response was lost: the pair from the first rotation is replaced, not added to.
            refreshTokens.revokeDescendants(token.id());
            return new Outcome.Rotated(tokens.issue(user, token.familyId(), token.id()));
        }
        refreshTokens.revokeFamily(token.familyId());
        auditLog.record(new AuditEntry(Actor.system("identity"), "token.reuse_detected", "user", user.id().toString(),
                "A rotated refresh token was used again after the grace period", null,
                Map.of("family_id", token.familyId().toString())));
        log.warn("Refresh token reuse detected: revoked token family {} of user {}", token.familyId(), user.id());
        return new Outcome.Refused(tokenInvalid());
    }

    private static ApiException tokenInvalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID",
                "The refresh token is not valid; sign in again.");
    }

    private sealed interface Outcome {

        record Rotated(SignedIn signedIn) implements Outcome {
        }

        /** Raised only after the transaction commits, so revocations and the audit entry stay. */
        record Refused(ApiException error) implements Outcome {
        }
    }
}

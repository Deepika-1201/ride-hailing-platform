package com.ridehailing.identity.app;

import com.ridehailing.identity.db.OtpChallenges;
import com.ridehailing.identity.db.OtpChallenges.Challenge;
import com.ridehailing.identity.db.Users;
import com.ridehailing.identity.db.Users.User;
import com.ridehailing.notification.NotificationApi;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.RateLimiter;
import com.ridehailing.platform.Transactions;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.Phones;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Sign-in with a one-time code sent by SMS (LLD §12.1). */
@Service
public class SignIn {

    static final String LIMIT_PER_PHONE = "otp-per-phone";
    static final String LIMIT_PER_IP = "otp-per-ip";

    private static final Logger log = LoggerFactory.getLogger(SignIn.class);

    private final RateLimiter rateLimiter;
    private final OneTimeCodes codes;
    private final OtpChallenges challenges;
    private final Users users;
    private final TokenPairs tokens;
    private final NotificationApi notifications;
    private final Transactions transactions;
    private final SignInProperties properties;

    SignIn(RateLimiter rateLimiter, OneTimeCodes codes, OtpChallenges challenges, Users users, TokenPairs tokens,
            NotificationApi notifications, Transactions transactions, SignInProperties properties) {
        this.rateLimiter = rateLimiter;
        this.codes = codes;
        this.challenges = challenges;
        this.users = users;
        this.tokens = tokens;
        this.notifications = notifications;
        this.transactions = transactions;
        this.properties = properties;
    }

    /** The same answer whether or not the phone has an account. */
    public CodeSent requestCode(String phone, String requestIp) {
        rateLimiter.acquireOrReject(LIMIT_PER_IP, requestIp);
        rateLimiter.acquireOrReject(LIMIT_PER_PHONE, phone);
        UUID id = Ids.newId();
        String code = codes.newCode();
        Instant expiresAt = transactions.execute(
                () -> challenges.create(id, phone, codes.hmac(id, code), properties.ttl(), requestIp));
        // After the commit, so a code is never sent for a challenge that doesn't exist.
        notifications.sendOneTimeCode(phone, code);
        log.info("Sent a one-time code to {}", Phones.mask(phone));
        return new CodeSent(expiresAt, properties.resendAfter());
    }

    public SignedIn exchange(String phone, String code) {
        return switch (transactions.execute(() -> verify(phone, code))) {
            case Outcome.Accepted accepted -> accepted.signedIn();
            case Outcome.Rejected rejected -> throw rejected.error();
        };
    }

    // Runs in one transaction, which commits even when the code is wrong: the attempt must count.
    private Outcome verify(String phone, String code) {
        Optional<Challenge> open = challenges.latestOpen(phone, properties.maxAttempts());
        if (open.isEmpty()) {
            return new Outcome.Rejected(codeInvalid());
        }
        Challenge challenge = open.get();
        if (!codes.matches(challenge.id(), code, challenge.codeHmac())) {
            int attempts = challenges.recordWrongCode(challenge.id());
            return new Outcome.Rejected(attempts >= properties.maxAttempts()
                    ? new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CODE_ATTEMPTS_EXCEEDED",
                            "Too many wrong codes; request a new one.")
                    : codeInvalid());
        }
        challenges.consume(challenge.id());
        User user = users.findOrCreateRider(phone);
        if (!user.active()) {
            return new Outcome.Rejected(Accounts.disabled());
        }
        return new Outcome.Accepted(tokens.issue(user, Ids.newId(), null));
    }

    // Wrong, expired, used up or never requested: deliberately the same answer.
    private static ApiException codeInvalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "CODE_INVALID", "The code is wrong or has expired.");
    }

    public record CodeSent(Instant expiresAt, Duration resendAfter) {
    }

    private sealed interface Outcome {

        record Accepted(SignedIn signedIn) implements Outcome {
        }

        /** Raised only after the transaction commits. */
        record Rejected(ApiException error) implements Outcome {
        }
    }
}

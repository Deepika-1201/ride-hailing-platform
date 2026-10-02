package com.ridehailing.identity.app;

import com.ridehailing.identity.db.OtpChallenges;
import com.ridehailing.identity.db.RefreshTokens;
import java.time.Duration;
import java.util.function.IntSupplier;
import org.springframework.stereotype.Service;

/** Deletes one-time codes after a day and refresh tokens a day after they expire (LLD §5.7). */
@Service
public class IdentityRetention {

    private static final Duration KEEP = Duration.ofDays(1);
    private static final int BATCH = 10_000;

    private final OtpChallenges challenges;
    private final RefreshTokens refreshTokens;

    IdentityRetention(OtpChallenges challenges, RefreshTokens refreshTokens) {
        this.challenges = challenges;
        this.refreshTokens = refreshTokens;
    }

    public void purge() {
        inBatches(() -> challenges.deleteOlderThan(KEEP, BATCH));
        inBatches(() -> refreshTokens.deleteExpiredBefore(KEEP, BATCH));
    }

    private static void inBatches(IntSupplier deleteBatch) {
        int deleted;
        do {
            deleted = deleteBatch.getAsInt();
        } while (deleted == BATCH);
    }
}

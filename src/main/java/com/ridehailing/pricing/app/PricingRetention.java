package com.ridehailing.pricing.app;

import com.ridehailing.pricing.db.QuoteRepository;
import com.ridehailing.pricing.db.ComputedSurgeRepository;
import java.time.Duration;
import org.springframework.stereotype.Service;

/** Deletes unused quotes a day after they expire, and used ones after 30 days (LLD §5.7). */
@Service
public class PricingRetention {

    static final Duration KEEP_UNUSED = Duration.ofHours(24);
    static final Duration KEEP_USED = Duration.ofDays(30);
    private static final int BATCH = 10_000;

    private final QuoteRepository quotes;
    private final QuoteProperties properties;
    private final ComputedSurgeRepository surge;

    PricingRetention(QuoteRepository quotes, QuoteProperties properties, ComputedSurgeRepository surge) {
        this.quotes = quotes;
        this.properties = properties;
        this.surge = surge;
    }

    public void purge() {
        int deleted;
        do {
            deleted = quotes.deleteOld(KEEP_UNUSED.plus(properties.ttl()), KEEP_USED, BATCH);
        } while (deleted == BATCH);
        do {
            deleted = surge.deleteHistory(BATCH);
        } while (deleted == BATCH);
    }
}

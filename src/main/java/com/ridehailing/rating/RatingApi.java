package com.ridehailing.rating;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Ratings, for the other modules (LLD §2.2, §13.4). */
public interface RatingApi {

    /** The person's rating as this party: the average of their latest 100 ratings, none if they have none. */
    RatingSummary summary(UUID userId, Party party);

    /** Summaries of those with ratings as this party; the others are missing from the map. */
    Map<UUID, RatingSummary> summaries(Collection<UUID> userIds, Party party);

    enum Party {
        RIDER,
        DRIVER
    }

    /** The RatingSummary schema of {@code openapi.yaml}; {@code average} is null without ratings. */
    record RatingSummary(BigDecimal average, int count) {

        public static final RatingSummary NONE = new RatingSummary(null, 0);
    }
}

package com.ridehailing.rating.app;

import com.ridehailing.rating.RatingApi;
import com.ridehailing.rating.db.RatingRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class RatingService implements RatingApi {

    private final RatingRepository ratings;

    RatingService(RatingRepository ratings) {
        this.ratings = ratings;
    }

    @Override
    public RatingSummary summary(UUID userId, Party party) {
        return ratings.summary(userId, party.name())
                .map(summary -> new RatingSummary(summary.average(), summary.count()))
                .orElse(RatingSummary.NONE);
    }
}

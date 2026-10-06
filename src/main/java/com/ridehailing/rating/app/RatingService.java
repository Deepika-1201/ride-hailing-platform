package com.ridehailing.rating.app;

import com.ridehailing.rating.RatingApi;
import com.ridehailing.rating.db.RatingRepository;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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

    @Override
    public Map<UUID, RatingSummary> summaries(Collection<UUID> userIds, Party party) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return ratings.summaries(userIds, party.name()).entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                entry -> new RatingSummary(entry.getValue().average(), entry.getValue().count())));
    }
}

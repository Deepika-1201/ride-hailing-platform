package com.ridehailing.rating.app;

import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.EventEnvelope;
import com.ridehailing.rating.db.RatingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** {@code rating.windows} (LLD §15.2, §13.4): a completed trip opens its rating window for 7 days (FR-RT1). */
@Component
class RatingWindows implements EventConsumer {

    static final String NAME = "rating.windows";
    static final Duration OPEN_FOR = Duration.ofDays(7);

    private final RatingRepository ratings;

    RatingWindows(RatingRepository ratings) {
        this.ratings = ratings;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("TripCompleted");
    }

    @Override
    public void handle(EventEnvelope event) {
        JsonNode trip = event.payload();
        ratings.openWindow(UUID.fromString(trip.get("ride_id").asString()),
                UUID.fromString(trip.get("rider_id").asString()), UUID.fromString(trip.get("driver_id").asString()),
                Instant.parse(trip.get("completed_at").asString()), OPEN_FOR);
    }
}

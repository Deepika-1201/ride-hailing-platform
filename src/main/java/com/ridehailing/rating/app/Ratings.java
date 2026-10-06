package com.ridehailing.rating.app;

import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.Transactions;
import com.ridehailing.rating.RatingApi.Party;
import com.ridehailing.rating.db.RatingRepository;
import com.ridehailing.rating.db.RatingRepository.RatingRow;
import com.ridehailing.rating.db.RatingRepository.WindowRow;
import com.ridehailing.rating.events.RatingSubmitted;
import com.ridehailing.shared.Ids;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Rating the other party of a completed ride, once per side within the window (FR-RT1, LLD §13.4). */
@Service
public class Ratings {

    private final RatingRepository ratings;
    private final Outbox outbox;
    private final Transactions transactions;

    Ratings(RatingRepository ratings, Outbox outbox, Transactions transactions) {
        this.ratings = ratings;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    /**
     * The caller rates as the window's rider or as its driver. The ratee's summary lock queues a concurrent second
     * rating by the same side behind the first, which it then finds.
     *
     * @throws ApiException {@code 409 RATING_NOT_OPEN} or {@code 409 ALREADY_RATED}
     */
    public RatingView rate(UUID rideId, UUID userId, int stars, String comment) {
        return transactions.execute(() -> {
            WindowRow window = ratings.openWindow(rideId).orElseThrow(Ratings::notOpen);
            Party rater;
            if (window.riderId().equals(userId)) {
                rater = Party.RIDER;
            } else if (window.driverId().equals(userId)) {
                rater = Party.DRIVER;
            } else {
                throw notOpen();
            }
            Party ratee = rater == Party.RIDER ? Party.DRIVER : Party.RIDER;
            UUID rateeId = rater == Party.RIDER ? window.driverId() : window.riderId();
            ratings.lockSummary(rateeId, ratee.name());
            if (ratings.rated(rideId, rater.name())) {
                throw alreadyRated();
            }
            RatingRow rating = ratings.insert(Ids.newId(), rideId, rater.name(), userId, rateeId, stars, comment);
            ratings.refreshSummary(rateeId, ratee.name(), rater.name());
            outbox.append(new DomainEvent(RatingSubmitted.TYPE, RatingSubmitted.VERSION, "rating", rating.id(), 0,
                    rideId, new RatingSubmitted(rating.id(), rideId, rater.name(), userId, rateeId, stars,
                            comment != null, rating.createdAt())));
            return new RatingView(rating.id(), rideId, rater.name(), stars, comment, rating.createdAt());
        });
    }

    private static ApiException notOpen() {
        return new ApiException(HttpStatus.CONFLICT, "RATING_NOT_OPEN",
                "Only the rider and the driver of a ride completed in the last 7 days can rate it.");
    }

    private static ApiException alreadyRated() {
        return new ApiException(HttpStatus.CONFLICT, "ALREADY_RATED", "You already rated this ride.");
    }

    /** The Rating schema of {@code openapi.yaml}. */
    public record RatingView(UUID id, UUID rideId, String raterRole, int stars, String comment, Instant createdAt) {
    }
}

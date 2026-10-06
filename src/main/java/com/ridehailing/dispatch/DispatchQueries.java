package com.ridehailing.dispatch;

import com.ridehailing.dispatch.DispatchApi.DriverStatusView;
import com.ridehailing.platform.Cursor;
import com.ridehailing.shared.Page;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Dispatch's records for operations (LLD §2.2, §13.5): the drivers list and what dispatch did for a ride. */
public interface DispatchQueries {

    /** Search attempts of the ride, oldest first (FR-DS6). */
    List<DecisionView> decisions(UUID rideId);

    /** Offers of the ride, oldest first. */
    List<OfferRecord> offers(UUID rideId);

    /**
     * Drivers who have been online, by their latest status change, newest first after the cursor; {@code cityId} and
     * {@code status} filter when not null.
     */
    Page<DriverStatusView> drivers(String cityId, AvailabilityStatus status, Cursor after, int limit);

    /** {@code detail} holds the candidates, exclusions and reservation tries (§8.3). */
    record DecisionView(int attempt, Instant createdAt, String strategy, String strategyVersion, int radiusM,
            String outcome, UUID chosenDriverId, UUID offerId, JsonNode detail, int durationUs) {
    }

    /** {@code respondedAt} is set by the driver's answer; {@code endReason} by a decline or a withdrawal. */
    record OfferRecord(UUID id, UUID driverId, int attempt, int rank, int distanceM, String status, String endReason,
            Instant createdAt, Instant expiresAt, Instant seenAt, Instant respondedAt) {
    }
}

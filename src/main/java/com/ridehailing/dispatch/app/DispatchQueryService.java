package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.AvailabilityStatus;
import com.ridehailing.dispatch.DispatchApi.DriverStatusView;
import com.ridehailing.dispatch.DispatchQueries;
import com.ridehailing.dispatch.db.AvailabilityRepository;
import com.ridehailing.dispatch.db.AvailabilityRepository.AvailabilityRow;
import com.ridehailing.dispatch.db.DecisionRepository;
import com.ridehailing.dispatch.db.OfferRepository;
import com.ridehailing.platform.Cursor;
import com.ridehailing.shared.Page;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
class DispatchQueryService implements DispatchQueries {

    private final DecisionRepository decisions;
    private final OfferRepository offers;
    private final AvailabilityRepository availability;
    private final JsonMapper json;

    DispatchQueryService(DecisionRepository decisions, OfferRepository offers, AvailabilityRepository availability,
            JsonMapper json) {
        this.decisions = decisions;
        this.offers = offers;
        this.availability = availability;
        this.json = json;
    }

    @Override
    public List<DecisionView> decisions(UUID rideId) {
        return decisions.ofRide(rideId).stream().map(row -> new DecisionView(row.attempt(), row.createdAt(),
                row.strategy(), row.strategyVersion(), row.radiusM(), row.outcome(), row.chosenDriverId(),
                row.offerId(), json.readTree(row.detail()), row.durationUs())).toList();
    }

    @Override
    public List<OfferRecord> offers(UUID rideId) {
        return offers.ofRide(rideId).stream().map(row -> new OfferRecord(row.id(), row.driverId(), row.attempt(),
                row.rank(), row.distanceM(), row.status().name(), row.endReason(), row.createdAt(), row.expiresAt(),
                row.seenAt(), row.respondedAt())).toList();
    }

    @Override
    public Page<DriverStatusView> drivers(String cityId, AvailabilityStatus status, Cursor after, int limit) {
        List<AvailabilityRow> rows = availability.list(cityId, status, after == null ? null : after.createdAt(),
                after == null ? null : after.id(), limit + 1);
        List<AvailabilityRow> page = rows.subList(0, Math.min(limit, rows.size()));
        String next = rows.size() > limit
                ? new Cursor(page.getLast().statusChangedAt(), page.getLast().driverId()).encode() : null;
        return new Page<>(page.stream().map(Availability::view).toList(), next);
    }
}

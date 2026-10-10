package com.ridehailing.ride.app;

import com.ridehailing.platform.Cursor;
import com.ridehailing.platform.InvariantCheck;
import com.ridehailing.platform.Timers;
import com.ridehailing.ride.RideQueries;
import com.ridehailing.ride.RideStatus;
import com.ridehailing.ride.RideView;
import com.ridehailing.ride.db.RideRepository;
import com.ridehailing.ride.db.RideRepository.RideRow;
import com.ridehailing.ride.db.TransitionRepository;
import com.ridehailing.shared.Page;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class RideQueryService implements RideQueries, InvariantCheck {

    static final Duration RECENTLY_ENDED = Duration.ofDays(1);

    private final RideRepository rides;
    private final TransitionRepository transitions;
    private final RideViews views;
    private final Timers timers;

    RideQueryService(RideRepository rides, TransitionRepository transitions, RideViews views, Timers timers) {
        this.rides = rides;
        this.transitions = transitions;
        this.views = views;
        this.timers = timers;
    }

    @Override
    public Optional<RideView> find(UUID rideId) {
        return rides.find(rideId).map(views::of);
    }

    @Override
    public Optional<RideView> activeRideOfRider(UUID riderId) {
        return rides.activeOfRider(riderId).map(views::of);
    }

    @Override
    public Page<RideView> list(RideFilter filter, Cursor after, int limit) {
        List<RideRow> rows = rides.list(filter.statuses(), filter.cityId(), after == null ? null : after.createdAt(),
                after == null ? null : after.id(), limit + 1);
        List<RideRow> page = rows.subList(0, Math.min(limit, rows.size()));
        String next = rows.size() > limit ? new Cursor(page.getLast().requestedAt(), page.getLast().id()).encode()
                : null;
        return new Page<>(page.stream().map(views::of).toList(), next);
    }

    @Override
    public List<TransitionView> transitions(UUID rideId) {
        return transitions.ofRide(rideId);
    }

    @Override
    public Optional<UUID> rideOfDriverAt(UUID driverId, Instant at) {
        return rides.ofDriverAt(driverId, at);
    }

    @Override
    public List<DrivenRide> drivenRides(String cityId) {
        return rides.drivenRides(cityId);
    }

    @Override
    public Map<UUID, RideStatus> statuses(Collection<UUID> rideIds) {
        return rideIds.isEmpty() ? Map.of() : rides.statuses(rideIds);
    }

    @Override
    public List<UUID> endedWithSearchTimer(String cityId) {
        List<UUID> ended = rides.endedWithin(cityId, RECENTLY_ENDED);
        Set<UUID> withTimer = timers.scheduled(RideTimers.SEARCH_TIMEOUT, ended);
        return ended.stream().filter(withTimer::contains).toList();
    }

    /** I1: no driver and no rider in two active rides. */
    @Override
    public String id() {
        return "I1";
    }

    @Override
    public List<String> violations(String cityId) {
        return rides.partiesInTwoActiveRides(cityId);
    }
}

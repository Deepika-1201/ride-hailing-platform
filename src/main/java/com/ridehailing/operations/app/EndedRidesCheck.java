package com.ridehailing.operations.app;

import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.platform.InvariantCheck;
import com.ridehailing.ride.RideQueries;
import com.ridehailing.ride.RideStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * I6: a ride that ended has no pending offer, no search task and no timer (LLD §17.3), from both sides: what dispatch
 * still holds, and the ride's own search timer.
 */
@Component
class EndedRidesCheck implements InvariantCheck {

    private final RideQueries rides;
    private final DispatchApi dispatch;

    EndedRidesCheck(RideQueries rides, DispatchApi dispatch) {
        this.rides = rides;
        this.dispatch = dispatch;
    }

    @Override
    public String id() {
        return "I6";
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<String> violations(String cityId) {
        Map<UUID, String> work = dispatch.dispatchWork(cityId);
        Map<UUID, RideStatus> statuses = rides.statuses(work.keySet());
        List<String> violations = new ArrayList<>();
        work.forEach((rideId, what) -> {
            RideStatus status = statuses.get(rideId);
            if (status != null && !status.active()) {
                violations.add("ride " + rideId + " is " + status + " but has " + what);
            }
        });
        rides.endedWithSearchTimer(cityId).forEach(rideId ->
                violations.add("ride " + rideId + " ended but its search timer is scheduled"));
        return violations;
    }
}

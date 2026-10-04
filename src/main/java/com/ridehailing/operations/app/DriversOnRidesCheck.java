package com.ridehailing.operations.app;

import com.ridehailing.dispatch.AvailabilityStatus;
import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.dispatch.DispatchApi.BusyDriver;
import com.ridehailing.platform.InvariantCheck;
import com.ridehailing.ride.RideQueries;
import com.ridehailing.ride.RideQueries.DrivenRide;
import com.ridehailing.ride.RideStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * I4: a driver is {@code ASSIGNED} or {@code ON_TRIP} exactly when a ride has them in the matching state (LLD §17.3),
 * read from ride and dispatch through their APIs in one snapshot.
 */
@Component
class DriversOnRidesCheck implements InvariantCheck {

    private final RideQueries rides;
    private final DispatchApi dispatch;

    DriversOnRidesCheck(RideQueries rides, DispatchApi dispatch) {
        this.rides = rides;
        this.dispatch = dispatch;
    }

    @Override
    public String id() {
        return "I4";
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<String> violations(String cityId) {
        Map<UUID, BusyDriver> busy = dispatch.busyDrivers(cityId).stream()
                .collect(Collectors.toMap(BusyDriver::driverId, Function.identity()));
        // A driver in two rides breaks I1, which reports it; here each of their rides is compared on its own.
        Map<UUID, List<DrivenRide>> driven = rides.drivenRides(cityId).stream()
                .collect(Collectors.groupingBy(DrivenRide::driverId));
        List<String> violations = new ArrayList<>();
        driven.forEach((driverId, theirRides) -> theirRides.forEach(ride -> {
            BusyDriver driver = busy.get(driverId);
            AvailabilityStatus expected = ride.status() == RideStatus.IN_TRIP
                    ? AvailabilityStatus.ON_TRIP : AvailabilityStatus.ASSIGNED;
            if (driver == null || driver.status() != expected || !ride.rideId().equals(driver.rideId())) {
                violations.add("driver " + driverId + " drives ride " + ride.rideId() + " (" + ride.status()
                        + ") but is " + (driver == null ? "neither ASSIGNED nor ON_TRIP"
                        : driver.status() + " on ride " + driver.rideId()));
            }
        }));
        busy.forEach((driverId, driver) -> {
            if (!driven.containsKey(driverId)) {
                violations.add("driver " + driverId + " is " + driver.status() + " on ride " + driver.rideId()
                        + ", which they don't drive");
            }
        });
        return violations;
    }
}

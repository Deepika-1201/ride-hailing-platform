package com.ridehailing.ride.web;

import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Cursor;
import com.ridehailing.ride.RideView;
import com.ridehailing.ride.app.MyRides;
import com.ridehailing.shared.Page;
import com.ridehailing.shared.UserRole;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The caller's own rides (LLD §13.6): the history and the active ride, for riders and for drivers. */
@ApiController
class MyRidesController {

    private final MyRides rides;

    MyRidesController(MyRides rides) {
        this.rides = rides;
    }

    @GetMapping("/v1/riders/me/rides")
    @AllowedRoles(UserRole.RIDER)
    Page<RideView> riderRides(Caller caller, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return rides.ofRider(caller.userId(), Cursor.decode(cursor), Cursor.limit(limit));
    }

    @GetMapping("/v1/riders/me/active-ride")
    @AllowedRoles(UserRole.RIDER)
    ResponseEntity<RideView> riderActiveRide(Caller caller) {
        return okOrNoContent(rides.activeOfRider(caller.userId()));
    }

    @GetMapping("/v1/drivers/me/rides")
    @AllowedRoles(UserRole.DRIVER)
    Page<RideView> driverRides(Caller caller, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return rides.ofDriver(caller.userId(), Cursor.decode(cursor), Cursor.limit(limit));
    }

    @GetMapping("/v1/drivers/me/active-ride")
    @AllowedRoles(UserRole.DRIVER)
    ResponseEntity<RideView> driverActiveRide(Caller caller) {
        return okOrNoContent(rides.activeOfDriver(caller.userId()));
    }

    private static ResponseEntity<RideView> okOrNoContent(Optional<RideView> ride) {
        return ride.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
}

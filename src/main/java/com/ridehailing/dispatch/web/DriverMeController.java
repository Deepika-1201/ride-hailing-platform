package com.ridehailing.dispatch.web;

import com.ridehailing.driver.DriverApi;
import com.ridehailing.driver.DriverApi.DriverProfile;
import com.ridehailing.driver.DriverApi.Vehicle;
import com.ridehailing.driver.Verification;
import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Caller;
import com.ridehailing.shared.UserRole;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The signed-in driver's profile and availability. Dispatch serves it because it owns availability (LLD §13.3);
 * until phase 6 creates availability rows, every driver is {@code OFFLINE} at version 0.
 */
@ApiController
@AllowedRoles(UserRole.DRIVER)
@RequestMapping("/v1/drivers/me")
class DriverMeController {

    private final DriverApi drivers;

    DriverMeController(DriverApi drivers) {
        this.drivers = drivers;
    }

    @GetMapping
    DriverView me(Caller caller) {
        DriverProfile profile = drivers.profile(caller.userId()).orElseThrow(ApiException::notFound);
        return new DriverView(profile.id(), profile.firstName(), profile.lastName(), profile.cityId(),
                profile.verification(), profile.suspended(), profile.vehicles(),
                new StatusView(profile.id(), "OFFLINE", 0));
    }

    record DriverView(UUID id, String firstName, String lastName, String cityId, Verification verification,
            boolean suspended, List<Vehicle> vehicles, StatusView status) {
    }

    record StatusView(UUID driverId, String status, long version) {
    }
}

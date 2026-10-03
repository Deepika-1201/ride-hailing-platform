package com.ridehailing.driver.app;

import com.ridehailing.driver.DriverApi;
import com.ridehailing.driver.DriverApi.Vehicle;
import com.ridehailing.driver.Verification;
import com.ridehailing.driver.db.DriverRepository;
import com.ridehailing.driver.db.DriverRepository.DriverRow;
import com.ridehailing.driver.db.VehicleRepository;
import com.ridehailing.geography.GeographyApi;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class DriverService implements DriverApi {

    private final DriverRepository drivers;
    private final VehicleRepository vehicles;
    private final GeographyApi geography;

    DriverService(DriverRepository drivers, VehicleRepository vehicles, GeographyApi geography) {
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.geography = geography;
    }

    @Override
    public Optional<DriverProfile> profile(UUID driverId) {
        return drivers.find(driverId).map(row -> new DriverProfile(row.id(), row.firstName(), row.lastName(),
                row.cityId(), row.verification(), row.suspended(), vehicles.ofDriver(driverId)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Eligibility lockEligibility(UUID driverId, UUID vehicleId) {
        Optional<DriverRow> found = drivers.lockForShare(driverId);
        if (found.isEmpty()) {
            return Eligibility.refused("You don't have a driver profile.");
        }
        DriverRow driver = found.get();
        if (driver.verification() != Verification.VERIFIED) {
            return Eligibility.refused("Your account isn't verified.");
        }
        if (driver.suspended()) {
            return Eligibility.refused("Your account is suspended.");
        }
        Optional<Vehicle> vehicle = vehicles.lockForShare(vehicleId).filter(v -> v.driverId().equals(driverId));
        if (vehicle.isEmpty()) {
            return Eligibility.refused("This vehicle isn't registered to you.");
        }
        if (!vehicle.get().active()) {
            return Eligibility.refused("This vehicle is inactive.");
        }
        if (!geography.offers(driver.cityId(), vehicle.get().category())) {
            return Eligibility.refused("Your city doesn't offer this vehicle's category now.");
        }
        return new Eligibility(null, driver.cityId(), vehicle.get().category());
    }
}

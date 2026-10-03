package com.ridehailing.driver.app;

import com.ridehailing.driver.DriverApi;
import com.ridehailing.driver.db.DriverRepository;
import com.ridehailing.driver.db.VehicleRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class DriverService implements DriverApi {

    private final DriverRepository drivers;
    private final VehicleRepository vehicles;

    DriverService(DriverRepository drivers, VehicleRepository vehicles) {
        this.drivers = drivers;
        this.vehicles = vehicles;
    }

    @Override
    public Optional<DriverProfile> profile(UUID driverId) {
        return drivers.find(driverId).map(row -> new DriverProfile(row.id(), row.firstName(), row.lastName(),
                row.cityId(), row.verification(), row.suspended(), vehicles.ofDriver(driverId)));
    }
}

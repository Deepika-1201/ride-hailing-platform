package com.ridehailing.driver;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Drivers and their vehicles, for the other modules (LLD §2.2). */
public interface DriverApi {

    /** The driver's profile with all their vehicles; empty if the user isn't a driver. */
    Optional<DriverProfile> profile(UUID driverId);

    record DriverProfile(UUID id, String firstName, String lastName, String cityId, Verification verification,
            boolean suspended, List<Vehicle> vehicles) {

        public DriverProfile {
            vehicles = List.copyOf(vehicles);
        }
    }

    record Vehicle(UUID id, UUID driverId, String category, String plate, String make, String model, String colour,
            boolean active, int version) {
    }
}

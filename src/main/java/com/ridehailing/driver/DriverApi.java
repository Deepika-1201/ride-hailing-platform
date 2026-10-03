package com.ridehailing.driver;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Drivers and their vehicles, for the other modules (LLD §2.2). */
public interface DriverApi {

    /** The driver's profile with all their vehicles; empty if the user isn't a driver. */
    Optional<DriverProfile> profile(UUID driverId);

    /**
     * Whether the driver may go online with the vehicle (LLD §8.2). Locks both rows {@code FOR SHARE} until the
     * caller's transaction ends, so neither changes while the driver goes online; requires that transaction.
     */
    Eligibility lockEligibility(UUID driverId, UUID vehicleId);

    /** What a ride keeps of its driver at assignment (LLD §8.4); empty if the vehicle isn't the driver's. */
    Optional<DriverSnapshot> snapshot(UUID driverId, UUID vehicleId);

    /** Ratings join it in phase 10. */
    record DriverSnapshot(UUID driverId, String firstName, Vehicle vehicle) {
    }

    /** The driver's city and the vehicle's category, or why the driver may not go online with it. */
    record Eligibility(String refusal, String cityId, String category) {

        public static Eligibility refused(String reason) {
            return new Eligibility(reason, null, null);
        }

        public boolean eligible() {
            return refusal == null;
        }
    }

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

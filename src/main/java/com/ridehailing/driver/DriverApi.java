package com.ridehailing.driver;

import com.ridehailing.rating.RatingApi.RatingSummary;
import com.ridehailing.shared.Actor;
import java.time.Instant;
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

    /**
     * Suspends the driver (LLD §8.8) in the caller's transaction, which it requires; false if they are suspended
     * already, which changes nothing. {@code 404} if the user isn't a driver.
     */
    boolean suspend(UUID driverId, Actor ops, String reason);

    /** Lifts the suspension (LLD §8.8) in the caller's transaction; false if the driver isn't suspended. */
    boolean reinstate(UUID driverId, Actor ops, String reason);

    /** The driver as admins and operations see them; empty if the user isn't a driver. */
    Optional<AdminDriver> admin(UUID driverId);

    /** Suspended drivers of the city, or of every city when {@code cityId} is null (I8). */
    List<UUID> suspended(String cityId);

    /** The rating comes from the rating module (LLD §13.4). */
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

    /** The AdminDriver schema of {@code openapi.yaml}, but for {@code status}, which dispatch knows. */
    record AdminDriver(UUID id, String firstName, String lastName, String cityId, Verification verification,
            boolean suspended, String suspensionReason, RatingSummary rating, List<Vehicle> vehicles,
            Instant createdAt) {

        public AdminDriver {
            vehicles = List.copyOf(vehicles);
        }
    }
}

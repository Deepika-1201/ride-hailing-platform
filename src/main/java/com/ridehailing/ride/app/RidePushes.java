package com.ridehailing.ride.app;

import com.ridehailing.platform.PushBus;
import com.ridehailing.platform.Transactions;
import com.ridehailing.rating.RatingApi.RatingSummary;
import com.ridehailing.ride.RideStatus;
import com.ridehailing.ride.RideView;
import com.ridehailing.ride.RideView.PersonSummary;
import com.ridehailing.ride.RideView.VehicleSummary;
import com.ridehailing.ride.db.RideRepository.RideRow;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code ride_status} after every transition commits (LLD §14.7): to the rider with the driver and vehicle, to the
 * ride's driver with the rider, and to a driver the transition released, who gets the status alone.
 */
@Component
class RidePushes {

    private final PushBus push;
    private final Transactions transactions;
    private final RideViews views;

    RidePushes(PushBus push, Transactions transactions, RideViews views) {
        this.push = push;
        this.transactions = transactions;
        this.views = views;
    }

    void transitioned(RideRow before, RideRow after) {
        RideView ride = views.of(after);
        RideStatusMessage toRider = new RideStatusMessage(ride.id(), ride.status(), ride.version(),
                Person.of(ride.driver()), Vehicle.of(ride.vehicle()), null, ride.promisedPickupEtaS());
        RideStatusMessage toDriver = new RideStatusMessage(ride.id(), ride.status(), ride.version(), null, null,
                Person.of(ride.rider()), null);
        UUID released = before == null || before.driverId() == null || before.driverId().equals(after.driverId())
                ? null : before.driverId();
        transactions.afterCommit(() -> {
            push.publish(PushBus.riderChannel(after.riderId()), toRider);
            if (after.driverId() != null) {
                push.publish(PushBus.driverChannel(after.driverId()), toDriver);
            }
            if (released != null) {
                push.publish(PushBus.driverChannel(released), new RideStatusMessage(ride.id(), ride.status(),
                        ride.version(), null, null, null, null));
            }
        });
    }

    /** The ride_status message of {@code server-messages.v1.json}. */
    record RideStatusMessage(String type, UUID rideId, RideStatus status, int version, Person driver, Vehicle vehicle,
            Person rider, Integer promisedPickupEtaS) {

        RideStatusMessage(UUID rideId, RideStatus status, int version, Person driver, Vehicle vehicle, Person rider,
                Integer promisedPickupEtaS) {
            this("ride_status", rideId, status, version, driver, vehicle, rider, promisedPickupEtaS);
        }
    }

    record Person(String firstName, RatingSummary rating) {

        static Person of(PersonSummary person) {
            return person == null ? null : new Person(person.firstName(), person.rating());
        }
    }

    record Vehicle(String make, String model, String colour, String plate) {

        static Vehicle of(VehicleSummary vehicle) {
            return vehicle == null ? null : new Vehicle(vehicle.make(), vehicle.model(), vehicle.colour(),
                    vehicle.plate());
        }
    }
}

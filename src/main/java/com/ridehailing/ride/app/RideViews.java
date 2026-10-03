package com.ridehailing.ride.app;

import com.ridehailing.ride.RideView;
import com.ridehailing.ride.RideView.Cancellation;
import com.ridehailing.ride.RideView.PaymentMethodRef;
import com.ridehailing.ride.RideView.PersonSummary;
import com.ridehailing.ride.RideView.VehicleSummary;
import com.ridehailing.ride.db.RideRepository.RideRow;
import com.ridehailing.shared.Money;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Rides as views, and the snapshots they keep of their rider and driver as JSON. */
@Component
class RideViews {

    private final JsonMapper json;

    RideViews(JsonMapper json) {
        this.json = json;
    }

    RideView of(RideRow ride) {
        RiderJson rider = json.readValue(ride.riderSnapshot(), RiderJson.class);
        DriverJson driver = ride.driverSnapshot() == null ? null : json.readValue(ride.driverSnapshot(),
                DriverJson.class);
        return new RideView(ride.id(), ride.status(), ride.version(), ride.cityId(), ride.category(), ride.pickup(),
                ride.dropoff(), new Money(ride.farePaise(), ride.currency()),
                new PaymentMethodRef(ride.paymentMethodId(), ride.paymentMethodType()), ride.pin(),
                ride.promisedPickupEtaS(),
                driver == null || driver.firstName() == null ? null : new PersonSummary(ride.driverId(),
                        driver.firstName()),
                driver == null ? null : driver.vehicle(),
                rider.firstName() == null ? null : new PersonSummary(ride.riderId(), rider.firstName()),
                ride.cancelledBy() == null ? null : new Cancellation(ride.cancelledBy(), ride.cancelReason()),
                ride.requestedAt(), ride.assignedAt(), ride.arrivedAt(), ride.startedAt(), ride.completedAt(),
                ride.endedAt(), ride.riderId(), ride.driverId(), ride.offerId());
    }

    String riderSnapshot(String firstName) {
        return json.writeValueAsString(new RiderJson(firstName));
    }

    String driverSnapshot(String firstName, VehicleSummary vehicle) {
        return json.writeValueAsString(new DriverJson(firstName, vehicle));
    }

    record RiderJson(String firstName) {
    }

    record DriverJson(String firstName, VehicleSummary vehicle) {
    }
}

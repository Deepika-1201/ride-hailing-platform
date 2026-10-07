package com.ridehailing.ride.app;

import com.ridehailing.pricing.PricingApi.FareBreakdown;
import com.ridehailing.rating.RatingApi.RatingSummary;
import com.ridehailing.ride.RideStatus;
import com.ridehailing.ride.RideView;
import com.ridehailing.ride.RideView.Cancellation;
import com.ridehailing.ride.RideView.Fee;
import com.ridehailing.ride.RideView.PaymentMethodRef;
import com.ridehailing.ride.RideView.PersonSummary;
import com.ridehailing.ride.RideView.TripEarnings;
import com.ridehailing.ride.RideView.VehicleSummary;
import com.ridehailing.ride.db.RideRepository.RideRow;
import com.ridehailing.shared.Money;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Rides as views, and the snapshots they keep of their rider, driver and quoted fare as JSON. */
@Component
class RideViews {

    private static final String CASH = "CASH";

    private final JsonMapper json;

    RideViews(JsonMapper json) {
        this.json = json;
    }

    RideView of(RideRow ride) {
        RiderJson rider = json.readValue(ride.riderSnapshot(), RiderJson.class);
        DriverJson driver = ride.driverSnapshot() == null ? null : json.readValue(ride.driverSnapshot(),
                DriverJson.class);
        Money fare = new Money(ride.farePaise(), ride.currency());
        return new RideView(ride.id(), ride.status(), ride.version(), ride.cityId(), ride.category(), ride.pickup(),
                ride.dropoff(), fare, new PaymentMethodRef(ride.paymentMethodId(), ride.paymentMethodType()),
                ride.pin(), ride.promisedPickupEtaS(),
                driver == null || driver.firstName() == null ? null : new PersonSummary(ride.driverId(),
                        driver.firstName(), driver.rating()),
                driver == null ? null : driver.vehicle(),
                rider.firstName() == null ? null : new PersonSummary(ride.riderId(), rider.firstName(),
                        rider.rating()),
                ride.cancelledBy() == null ? null : new Cancellation(ride.cancelledBy(), ride.cancelReason(),
                        ride.feePurpose() == null ? null : new Fee(ride.feePurpose(),
                                new Money(ride.feePaise(), ride.currency()))),
                ride.status() == RideStatus.COMPLETED ? earnings(ride, fare) : null,
                ride.requestedAt(), ride.assignedAt(), ride.arrivedAt(), ride.startedAt(), ride.completedAt(),
                ride.endedAt(), ride.riderId(), ride.driverId(), ride.offerId());
    }

    /** The quoted breakdown as the ride keeps it; null for rides booked before it was kept (LLD §13.6). */
    FareBreakdown breakdown(RideRow ride) {
        return ride.fareBreakdown() == null ? null : json.readValue(ride.fareBreakdown(), FareBreakdown.class);
    }

    String breakdown(FareBreakdown breakdown) {
        return json.writeValueAsString(breakdown);
    }

    String riderSnapshot(String firstName, RatingSummary rating) {
        return json.writeValueAsString(new RiderJson(firstName, rating));
    }

    String driverSnapshot(String firstName, VehicleSummary vehicle, RatingSummary rating) {
        return json.writeValueAsString(new DriverJson(firstName, vehicle, rating));
    }

    /** As the {@code TripCompleted} consumer of earnings counts the fare (LLD §11.8). */
    private static TripEarnings earnings(RideRow ride, Money fare) {
        return new TripEarnings(fare, new Money(ride.commissionPaise(), ride.currency()),
                new Money(ride.farePaise() - ride.commissionPaise(), ride.currency()),
                ride.paymentMethodType().equals(CASH) ? fare : new Money(0, ride.currency()));
    }

    /** Snapshots from before phase 10 have no rating. */
    record RiderJson(String firstName, RatingSummary rating) {
    }

    record DriverJson(String firstName, VehicleSummary vehicle, RatingSummary rating) {
    }
}

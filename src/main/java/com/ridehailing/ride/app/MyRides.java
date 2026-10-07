package com.ridehailing.ride.app;

import com.ridehailing.payment.PaymentApi;
import com.ridehailing.payment.PaymentApi.ChargeView;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Cursor;
import com.ridehailing.pricing.PricingApi.FareBreakdown;
import com.ridehailing.ride.RideStatus;
import com.ridehailing.ride.RideView;
import com.ridehailing.ride.RideView.PersonSummary;
import com.ridehailing.ride.RideView.VehicleSummary;
import com.ridehailing.ride.db.RideRepository;
import com.ridehailing.ride.db.RideRepository.RideRow;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Money;
import com.ridehailing.shared.Page;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** A rider's and a driver's own rides (LLD §13.6): their histories, their active ride, and the rider's receipts. */
@Service
public class MyRides {

    private final RideRepository rides;
    private final RideViews views;
    private final PaymentApi payments;

    MyRides(RideRepository rides, RideViews views, PaymentApi payments) {
        this.rides = rides;
        this.views = views;
        this.payments = payments;
    }

    public Page<RideView> ofRider(UUID riderId, Cursor after, int limit) {
        return page(rides.ofRider(riderId, after == null ? null : after.createdAt(), after == null ? null : after.id(),
                limit + 1), limit, RideView::forRider);
    }

    public Page<RideView> ofDriver(UUID driverId, Cursor after, int limit) {
        return page(rides.ofDriver(driverId, after == null ? null : after.createdAt(),
                after == null ? null : after.id(), limit + 1), limit, RideView::forDriver);
    }

    public Optional<RideView> activeOfRider(UUID riderId) {
        return rides.activeOfRider(riderId).map(views::of).map(RideView::forRider);
    }

    public Optional<RideView> activeOfDriver(UUID driverId) {
        return rides.activeOfDriver(driverId).map(views::of).map(RideView::forDriver);
    }

    /** The rider's completed ride: {@code 404} for anyone else's, {@code 409 RECEIPT_NOT_AVAILABLE} before then. */
    public Receipt receipt(UUID riderId, UUID rideId) {
        RideRow ride = rides.find(rideId).filter(found -> found.riderId().equals(riderId))
                .orElseThrow(ApiException::notFound);
        FareBreakdown fare = views.breakdown(ride);
        if (ride.status() != RideStatus.COMPLETED || fare == null) {
            throw new ApiException(HttpStatus.CONFLICT, "RECEIPT_NOT_AVAILABLE",
                    "This ride has no receipt; completed rides do.");
        }
        RideView view = views.of(ride);
        List<ChargeSummary> charges = payments.ofRide(rideId).charges().stream().map(ChargeSummary::of).toList();
        return new Receipt(ride.id(), ride.cityId(), ride.category(), ride.pickup(), ride.dropoff(), ride.distanceM(),
                ride.durationS(), fare, new ReceiptPayment(ride.paymentMethodType(), charges), view.driver(),
                view.vehicle(), ride.startedAt(), ride.completedAt());
    }

    private Page<RideView> page(List<RideRow> rows, int limit, UnaryOperator<RideView> party) {
        List<RideRow> page = rows.subList(0, Math.min(limit, rows.size()));
        String next = rows.size() > limit ? new Cursor(page.getLast().requestedAt(), page.getLast().id()).encode()
                : null;
        return new Page<>(page.stream().map(views::of).map(party).toList(), next);
    }

    /** The Receipt schema of {@code openapi.yaml}. */
    public record Receipt(UUID rideId, String cityId, String category, GeoPoint pickup, GeoPoint dropoff,
            int distanceM, int durationS, FareBreakdown fare, ReceiptPayment payment, PersonSummary driver,
            VehicleSummary vehicle, Instant startedAt, Instant completedAt) {
    }

    public record ReceiptPayment(String methodType, List<ChargeSummary> charges) {

        public ReceiptPayment {
            charges = List.copyOf(charges);
        }
    }

    /** The ChargeSummary schema: a charge without its attempts. */
    public record ChargeSummary(UUID id, UUID rideId, String purpose, Money amount, String status, String methodType,
            String failureCode, Instant createdAt, Instant updatedAt) {

        static ChargeSummary of(ChargeView charge) {
            return new ChargeSummary(charge.id(), charge.rideId(), charge.purpose(), charge.amount(), charge.status(),
                    charge.methodType(), charge.failureCode(), charge.createdAt(), charge.updatedAt());
        }
    }
}

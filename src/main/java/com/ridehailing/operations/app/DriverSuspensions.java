package com.ridehailing.operations.app;

import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.dispatch.DispatchApi.DriverStatusView;
import com.ridehailing.driver.DriverApi;
import com.ridehailing.driver.DriverApi.AdminDriver;
import com.ridehailing.driver.DriverApi.Vehicle;
import com.ridehailing.driver.Verification;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Transactions;
import com.ridehailing.rating.RatingApi.RatingSummary;
import com.ridehailing.shared.Actor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Suspending and reinstating drivers (FR-D5, LLD §8.8): the driver's side, then dispatch's, in one transaction. */
@Service
public class DriverSuspensions {

    private final DriverApi drivers;
    private final DispatchApi dispatch;
    private final Transactions transactions;

    DriverSuspensions(DriverApi drivers, DispatchApi dispatch, Transactions transactions) {
        this.drivers = drivers;
        this.dispatch = dispatch;
        this.transactions = transactions;
    }

    public SuspendableDriver suspend(UUID driverId, Actor ops, String reason) {
        return transactions.execute(() -> {
            if (drivers.suspend(driverId, ops, reason)) {
                dispatch.driverSuspended(driverId, ops);
            }
            return view(driverId);
        });
    }

    public SuspendableDriver reinstate(UUID driverId, Actor ops, String reason) {
        return transactions.execute(() -> {
            if (drivers.reinstate(driverId, ops, reason)) {
                dispatch.driverReinstated(driverId);
            }
            return view(driverId);
        });
    }

    private SuspendableDriver view(UUID driverId) {
        AdminDriver driver = drivers.admin(driverId).orElseThrow(ApiException::notFound);
        return new SuspendableDriver(driver.id(), driver.firstName(), driver.lastName(), driver.cityId(),
                driver.verification(), driver.suspended(), driver.suspensionReason(), driver.rating(),
                driver.vehicles(), dispatch.status(driverId), driver.createdAt());
    }

    /** The AdminDriver schema of {@code openapi.yaml}, with the driver's availability. */
    public record SuspendableDriver(UUID id, String firstName, String lastName, String cityId,
            Verification verification, boolean suspended, String suspensionReason, RatingSummary rating,
            List<Vehicle> vehicles, DriverStatusView status, Instant createdAt) {
    }
}

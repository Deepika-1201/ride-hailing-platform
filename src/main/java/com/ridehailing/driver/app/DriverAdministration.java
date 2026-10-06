package com.ridehailing.driver.app;

import com.ridehailing.audit.AuditEntry;
import com.ridehailing.audit.AuditLog;
import com.ridehailing.driver.DriverApi.AdminDriver;
import com.ridehailing.driver.DriverApi.Vehicle;
import com.ridehailing.driver.Verification;
import com.ridehailing.driver.db.DriverRepository;
import com.ridehailing.driver.db.DriverRepository.DriverRow;
import com.ridehailing.driver.db.VehicleRepository;
import com.ridehailing.driver.events.DriverReinstated;
import com.ridehailing.driver.events.DriverSuspended;
import com.ridehailing.driver.events.DriverVerified;
import com.ridehailing.geography.GeographyApi;
import com.ridehailing.identity.IdentityApi;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Cursor;
import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.Transactions;
import com.ridehailing.rating.RatingApi;
import com.ridehailing.rating.RatingApi.Party;
import com.ridehailing.rating.RatingApi.RatingSummary;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.Page;
import com.ridehailing.shared.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Onboarding: drivers, their verification and their vehicles, managed by admins (LLD §13.3). */
@Service
public class DriverAdministration {

    private final DriverRepository drivers;
    private final VehicleRepository vehicles;
    private final IdentityApi identity;
    private final GeographyApi geography;
    private final RatingApi ratings;
    private final AuditLog auditLog;
    private final Outbox outbox;
    private final Transactions transactions;
    private final Clock clock;

    DriverAdministration(DriverRepository drivers, VehicleRepository vehicles, IdentityApi identity,
            GeographyApi geography, RatingApi ratings, AuditLog auditLog, Outbox outbox, Transactions transactions,
            Clock clock) {
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.identity = identity;
        this.geography = geography;
        this.ratings = ratings;
        this.auditLog = auditLog;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Page<AdminDriver> list(String cityId, Verification verification, Cursor after, int limit) {
        List<DriverRow> rows = drivers.list(cityId, verification, after, limit + 1);
        List<DriverRow> page = rows.subList(0, Math.min(limit, rows.size()));
        List<UUID> ids = page.stream().map(DriverRow::id).toList();
        Map<UUID, List<Vehicle>> byDriver = vehicles.ofDrivers(ids);
        Map<UUID, RatingSummary> rated = ratings.summaries(ids, Party.DRIVER);
        String next = null;
        if (rows.size() > limit) {
            DriverRow last = page.getLast();
            next = new Cursor(last.createdAt(), last.id()).encode();
        }
        return new Page<>(page.stream().map(row -> admin(row, byDriver.getOrDefault(row.id(), List.of()),
                rated.getOrDefault(row.id(), RatingSummary.NONE))).toList(), next);
    }

    public AdminDriver get(UUID driverId) {
        return find(driverId).orElseThrow(ApiException::notFound);
    }

    Optional<AdminDriver> find(UUID driverId) {
        return drivers.find(driverId).map(row -> admin(row, vehicles.ofDriver(driverId),
                ratings.summary(driverId, Party.DRIVER)));
    }

    /** Creates the user or gives the phone's user the driver role, in the same transaction. */
    public AdminDriver create(Caller admin, String phone, String firstName, String lastName, String cityId) {
        return transactions.execute(() -> {
            if (geography.city(cityId).isEmpty()) {
                throw ApiException.invalid("city_id", "is not a known city");
            }
            UUID driverId = identity.ensureUser(phone, Set.of(UserRole.DRIVER));
            if (!drivers.insert(driverId, cityId, firstName, lastName)) {
                throw ApiException.alreadyExists("This phone belongs to a driver already.");
            }
            audit(admin, "driver.create", driverId, null, null, Map.of("city_id", cityId, "verification",
                    Verification.PENDING.name()));
            return get(driverId);
        });
    }

    /** Setting the current status again changes nothing; becoming {@code VERIFIED} publishes DriverVerified. */
    public AdminDriver setVerification(Caller admin, UUID driverId, Verification wanted, String reason) {
        return transactions.execute(() -> {
            DriverRow current = drivers.lock(driverId).orElseThrow(ApiException::notFound);
            if (current.verification() == wanted) {
                return get(driverId);
            }
            Instant now = clock.instant();
            DriverRow updated = drivers.setVerification(driverId, wanted);
            drivers.recordStatusChange(Ids.newId(), driverId, "VERIFICATION", current.verification().name(),
                    wanted.name(), reason, admin.userId().toString(), now);
            audit(admin, "driver.verification", driverId, reason,
                    Map.of("verification", current.verification().name()), Map.of("verification", wanted.name()));
            if (wanted == Verification.VERIFIED) {
                outbox.append(DomainEvent.of(DriverVerified.TYPE, DriverVerified.VERSION, "driver", driverId,
                        updated.version(), new DriverVerified(driverId, updated.cityId(), now, admin.userId())));
            }
            return get(driverId);
        });
    }

    /** The category must be offered in the driver's city. */
    public Vehicle addVehicle(Caller admin, UUID driverId, String category, String plate, String make, String model,
            String colour) {
        return transactions.execute(() -> {
            DriverRow driver = drivers.find(driverId).orElseThrow(ApiException::notFound);
            if (!geography.offers(driver.cityId(), category)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "CATEGORY_NOT_AVAILABLE",
                        "The driver's city doesn't offer this category.");
            }
            Vehicle vehicle = new Vehicle(Ids.newId(), driverId, category, plate, make, model, colour, true, 0);
            if (!vehicles.insert(vehicle)) {
                throw ApiException.alreadyExists("A vehicle with this plate exists already.");
            }
            audit(admin, "vehicle.create", vehicle.id(), null, null, Map.of("driver_id", driverId.toString(),
                    "category", category, "plate", plate));
            return vehicle;
        });
    }

    public Vehicle setVehicleActive(Caller admin, UUID vehicleId, boolean active, int version) {
        return transactions.execute(() -> {
            Vehicle current = vehicles.find(vehicleId).orElseThrow(ApiException::notFound);
            Vehicle updated = vehicles.setActive(vehicleId, active, version)
                    .orElseThrow(() -> ApiException.versionConflict(current.version()));
            Map<String, Object> before = new HashMap<>();
            before.put("active", current.active());
            audit(admin, "vehicle.update", vehicleId, null, before, Map.of("active", active));
            return updated;
        });
    }

    /** In the caller's transaction (LLD §8.8); false if the driver is suspended already, which keeps the first reason. */
    boolean suspend(UUID driverId, Actor ops, String reason) {
        DriverRow current = drivers.lock(driverId).orElseThrow(ApiException::notFound);
        if (current.suspended()) {
            return false;
        }
        Instant now = clock.instant();
        DriverRow updated = drivers.setSuspended(driverId, true, reason);
        drivers.recordStatusChange(Ids.newId(), driverId, "SUSPENSION", "ACTIVE", "SUSPENDED", reason, ops.id(), now);
        auditLog.record(new AuditEntry(ops, "driver.suspend", "driver", driverId.toString(), reason,
                Map.of("suspended", false), Map.of("suspended", true)));
        outbox.append(DomainEvent.of(DriverSuspended.TYPE, DriverSuspended.VERSION, "driver", driverId,
                updated.version(), new DriverSuspended(driverId, reason, UUID.fromString(ops.id()), now)));
        return true;
    }

    /** In the caller's transaction (LLD §8.8); false if the driver isn't suspended. */
    boolean reinstate(UUID driverId, Actor ops, String reason) {
        DriverRow current = drivers.lock(driverId).orElseThrow(ApiException::notFound);
        if (!current.suspended()) {
            return false;
        }
        Instant now = clock.instant();
        DriverRow updated = drivers.setSuspended(driverId, false, null);
        drivers.recordStatusChange(Ids.newId(), driverId, "REINSTATEMENT", "SUSPENDED", "ACTIVE", reason, ops.id(),
                now);
        auditLog.record(new AuditEntry(ops, "driver.reinstate", "driver", driverId.toString(), reason,
                Map.of("suspended", true), Map.of("suspended", false)));
        outbox.append(DomainEvent.of(DriverReinstated.TYPE, DriverReinstated.VERSION, "driver", driverId,
                updated.version(), new DriverReinstated(driverId, reason, UUID.fromString(ops.id()), now)));
        return true;
    }

    private void audit(Caller admin, String action, UUID entityId, String reason, Map<String, Object> before,
            Map<String, Object> after) {
        String entityType = action.substring(0, action.indexOf('.'));
        auditLog.record(new AuditEntry(admin.as(UserRole.ADMIN), action, entityType, entityId.toString(), reason,
                before, after));
    }

    private static AdminDriver admin(DriverRow row, List<Vehicle> vehicles, RatingSummary rating) {
        return new AdminDriver(row.id(), row.firstName(), row.lastName(), row.cityId(), row.verification(),
                row.suspended(), row.suspensionReason(), rating, vehicles, row.createdAt());
    }
}

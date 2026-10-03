package com.ridehailing.dispatch.app;

import com.ridehailing.audit.AuditEntry;
import com.ridehailing.audit.AuditLog;
import com.ridehailing.dispatch.AvailabilityStatus;
import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.dispatch.db.AvailabilityRepository;
import com.ridehailing.dispatch.db.AvailabilityRepository.AvailabilityRow;
import com.ridehailing.dispatch.db.SessionRepository;
import com.ridehailing.dispatch.events.DriverWentOffline;
import com.ridehailing.dispatch.events.DriverWentOnline;
import com.ridehailing.driver.DriverApi;
import com.ridehailing.driver.DriverApi.Eligibility;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.Transactions;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.Ids;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Going online and offline (LLD §8.1, §8.2): the row, the session, the event, the audit entry and the mirror. */
@Service
class Availability implements DispatchApi {

    static final String AGGREGATE = "availability";

    private final AvailabilityRepository availability;
    private final SessionRepository sessions;
    private final DriverApi drivers;
    private final Outbox outbox;
    private final AuditLog auditLog;
    private final Transactions transactions;
    private final LiveIndexMirror mirror;

    Availability(AvailabilityRepository availability, SessionRepository sessions, DriverApi drivers, Outbox outbox,
            AuditLog auditLog, Transactions transactions, LiveIndexMirror mirror) {
        this.availability = availability;
        this.sessions = sessions;
        this.drivers = drivers;
        this.outbox = outbox;
        this.auditLog = auditLog;
        this.transactions = transactions;
        this.mirror = mirror;
    }

    @Override
    public DriverStatusView goOnline(UUID driverId, UUID vehicleId) {
        return transactions.execute(() -> {
            Eligibility eligibility = drivers.lockEligibility(driverId, vehicleId);
            if (!eligibility.eligible()) {
                throw new ApiException(HttpStatus.CONFLICT, "DRIVER_NOT_ELIGIBLE", eligibility.refusal());
            }
            availability.insertOfflineIfAbsent(driverId, eligibility.cityId());
            AvailabilityRow row = availability.goOnline(driverId, eligibility.cityId(), eligibility.category(),
                    vehicleId).orElse(null);
            if (row == null) {
                AvailabilityRow current = availability.find(driverId).orElseThrow();
                if (vehicleId.equals(current.vehicleId())) {
                    return view(current);
                }
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                        "You're online with another vehicle; go offline first.");
            }
            sessions.open(Ids.newId(), driverId, row.cityId(), vehicleId, row.category());
            outbox.append(DomainEvent.of(DriverWentOnline.TYPE, DriverWentOnline.VERSION, AGGREGATE, driverId,
                    row.version(), new DriverWentOnline(driverId, row.cityId(), row.category(), vehicleId,
                            row.onlineSince())));
            auditLog.record(new AuditEntry(driver(driverId), "availability.online", AGGREGATE, driverId.toString(),
                    null, Map.of("status", AvailabilityStatus.OFFLINE.name()), Map.of("status", row.status().name(),
                            "vehicle_id", vehicleId.toString(), "category", row.category())));
            mirror.afterCommit(row);
            return view(row);
        });
    }

    @Override
    public DriverStatusView goOffline(UUID driverId) {
        return transactions.execute(() -> {
            AvailabilityRow current = availability.find(driverId).orElse(null);
            if (current == null) {
                return DriverStatusView.neverOnline(driverId);
            }
            refuseOfflineDuring(current.status());
            if (current.status() == AvailabilityStatus.OFFLINE) {
                return view(current);
            }
            AvailabilityRow locked = availability.lock(driverId).orElseThrow();
            refuseOfflineDuring(locked.status());
            if (locked.status() == AvailabilityStatus.OFFLINE) {
                return view(locked);
            }
            return view(takeOffline(locked, "DRIVER", driver(driverId)));
        });
    }

    @Override
    public DriverStatusView status(UUID driverId) {
        return availability.find(driverId).map(Availability::view)
                .orElseGet(() -> DriverStatusView.neverOnline(driverId));
    }

    /**
     * Takes an {@code AVAILABLE} driver offline, in the caller's transaction, which holds the row's lock: closes the
     * session with the reason, and writes the event and the audit entry.
     */
    AvailabilityRow takeOffline(AvailabilityRow locked, String reason, Actor actor) {
        AvailabilityRow offline = availability.goOffline(locked.driverId());
        sessions.close(locked.driverId(), reason);
        long onlineSeconds = Math.max(0, Duration.between(locked.onlineSince(), offline.statusChangedAt()).toSeconds());
        outbox.append(DomainEvent.of(DriverWentOffline.TYPE, DriverWentOffline.VERSION, AGGREGATE, locked.driverId(),
                offline.version(), new DriverWentOffline(locked.driverId(), offline.cityId(), reason, onlineSeconds,
                        offline.statusChangedAt())));
        auditLog.record(new AuditEntry(actor, "availability.offline", AGGREGATE, locked.driverId().toString(), reason,
                Map.of("status", locked.status().name()), Map.of("status", AvailabilityStatus.OFFLINE.name())));
        mirror.afterCommit(offline);
        return offline;
    }

    /** Phase 7 adds the decline step that lets a driver with an offer go offline (§8.2). */
    private static void refuseOfflineDuring(AvailabilityStatus status) {
        switch (status) {
            case ASSIGNED, ON_TRIP -> throw new ApiException(HttpStatus.CONFLICT, "DRIVER_HAS_ACTIVE_RIDE",
                    "You can't go offline during a ride.");
            case OFFERED -> throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                    "Answer your offer before going offline.");
            case OFFLINE, AVAILABLE -> {
            }
        }
    }

    private static Actor driver(UUID driverId) {
        return new Actor(Actor.Type.DRIVER, driverId.toString());
    }

    static DriverStatusView view(AvailabilityRow row) {
        return new DriverStatusView(row.driverId(), row.status(), row.cityId(), row.vehicleId(), row.category(),
                row.onlineSince(), row.offerId(), row.rideId(), row.version());
    }
}

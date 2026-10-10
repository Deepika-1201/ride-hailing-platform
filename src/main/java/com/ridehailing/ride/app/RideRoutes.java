package com.ridehailing.ride.app;

import com.ridehailing.audit.AuditEntry;
import com.ridehailing.audit.AuditLog;
import com.ridehailing.location.TripRoutes;
import com.ridehailing.location.TripRoutes.TripRoute;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Transactions;
import com.ridehailing.ride.db.RideRepository;
import com.ridehailing.ride.db.RideRepository.RideRow;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.UserRole;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Trip routes (LLD §9.8, FR-A2): the rider's and the ride's driver's own, and any ride's for operations, whose reads
 * are audited.
 */
@Service
public class RideRoutes {

    static final int MAX_POINTS = 2000;

    private final RideRepository rides;
    private final TripRoutes routes;
    private final AuditLog auditLog;
    private final Transactions transactions;

    RideRoutes(RideRepository rides, TripRoutes routes, AuditLog auditLog, Transactions transactions) {
        this.rides = rides;
        this.routes = routes;
        this.auditLog = auditLog;
        this.transactions = transactions;
    }

    /** {@code 404} for a ride that isn't the caller's, unless the caller is operations. */
    public TripRoute route(UUID userId, Set<UserRole> roles, UUID rideId) {
        RideRow ride = rides.find(rideId).orElseThrow(ApiException::notFound);
        if (ride.riderId().equals(userId) || userId.equals(ride.driverId())) {
            return routes.route(rideId, MAX_POINTS);
        }
        if (!roles.contains(UserRole.OPS)) {
            throw ApiException.notFound();
        }
        return transactions.execute(() -> {
            auditLog.record(new AuditEntry(new Actor(Actor.Type.OPS, userId.toString()), "route.read", "ride",
                    rideId.toString(), null, null, null));
            return routes.route(rideId, MAX_POINTS);
        });
    }
}

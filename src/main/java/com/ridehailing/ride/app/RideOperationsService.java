package com.ridehailing.ride.app;

import com.ridehailing.audit.AuditEntry;
import com.ridehailing.audit.AuditLog;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Cursor;
import com.ridehailing.platform.Transactions;
import com.ridehailing.ride.RideOperations;
import com.ridehailing.ride.RideView;
import com.ridehailing.ride.db.FlagRepository;
import com.ridehailing.ride.db.FlagRepository.FlagRow;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.Page;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Operations' side of rides (LLD §2.2): the system cancel and the review queue (§7.11, §13.5). */
@Service
class RideOperationsService implements RideOperations {

    private final Cancellations cancellations;
    private final FlagRepository flags;
    private final AuditLog auditLog;
    private final Transactions transactions;
    private final JsonMapper json;

    RideOperationsService(Cancellations cancellations, FlagRepository flags, AuditLog auditLog,
            Transactions transactions, JsonMapper json) {
        this.cancellations = cancellations;
        this.flags = flags;
        this.auditLog = auditLog;
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public RideView cancelBySystem(UUID rideId, Actor ops, String reason, Optional<FeeRequest> fee) {
        return cancellations.cancelBySystem(rideId, ops, reason, fee);
    }

    @Override
    public Page<FlagView> flags(FlagFilter filter, Cursor after, int limit) {
        List<FlagRow> rows = flags.list(filter.open(), filter.kind(), after == null ? null : after.createdAt(),
                after == null ? null : after.id(), limit + 1);
        List<FlagRow> page = rows.subList(0, Math.min(limit, rows.size()));
        String next = rows.size() > limit ? new Cursor(page.getLast().createdAt(), page.getLast().id()).encode() : null;
        return new Page<>(page.stream().map(this::view).toList(), next);
    }

    @Override
    public List<FlagView> flagsOfRide(UUID rideId) {
        return flags.ofRide(rideId).stream().map(this::view).toList();
    }

    @Override
    public FlagView resolveFlag(UUID flagId, Actor ops, String resolution) {
        return transactions.execute(() -> {
            flags.find(flagId).orElseThrow(ApiException::notFound);
            FlagRow resolved = flags.resolve(flagId, UUID.fromString(ops.id()), resolution)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "FLAG_ALREADY_RESOLVED",
                            "This flag is resolved already."));
            auditLog.record(new AuditEntry(ops, "flag.resolve", "flag", flagId.toString(), resolution,
                    Map.of("resolved", false), Map.of("resolved", true)));
            return view(resolved);
        });
    }

    private FlagView view(FlagRow row) {
        return new FlagView(row.id(), row.rideId(), row.kind(), json.readTree(row.details()), row.createdAt(),
                row.resolvedAt(), row.resolution());
    }
}

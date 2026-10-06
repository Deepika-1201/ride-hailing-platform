package com.ridehailing.operations.web;

import com.ridehailing.operations.app.RideTimeline;
import com.ridehailing.operations.app.RideTimeline.Timeline;
import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Cursor;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.IdempotentCall;
import com.ridehailing.ride.RideOperations;
import com.ridehailing.ride.RideOperations.FeeRequest;
import com.ridehailing.ride.RideQueries;
import com.ridehailing.ride.RideQueries.RideFilter;
import com.ridehailing.ride.RideStatus;
import com.ridehailing.ride.RideView;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.Page;
import com.ridehailing.shared.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Operations' rides: the list and the timeline (LLD §13.5), and the cancel (§7.4, T13). */
@ApiController
@AllowedRoles({UserRole.OPS, UserRole.ADMIN})
@RequestMapping("/v1/ops/rides")
class OpsRideController {

    private final RideOperations rides;
    private final RideQueries queries;
    private final RideTimeline timelines;
    private final Idempotency idempotency;

    OpsRideController(RideOperations rides, RideQueries queries, RideTimeline timelines, Idempotency idempotency) {
        this.rides = rides;
        this.queries = queries;
        this.timelines = timelines;
        this.idempotency = idempotency;
    }

    /** {@code status}: any of the statuses, comma-separated. */
    @GetMapping
    Page<RideView> rides(@RequestParam(required = false) List<String> status,
            @RequestParam(name = "city_id", required = false) String cityId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        Set<RideStatus> statuses = new HashSet<>();
        for (String name : status == null ? List.<String>of() : status) {
            try {
                statuses.add(RideStatus.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw ApiException.invalid("status", "must be ride statuses such as SEARCHING");
            }
        }
        Page<RideView> page = queries.list(new RideFilter(statuses, cityId), Cursor.decode(cursor),
                Cursor.limit(limit));
        return new Page<>(page.items().stream().map(RideView::forOperations).toList(), page.nextCursor());
    }

    @GetMapping("/{rideId}/timeline")
    Timeline timeline(@PathVariable UUID rideId) {
        return timelines.of(rideId);
    }

    @PostMapping("/{rideId}/cancel")
    ResponseEntity<?> cancel(Caller caller, @RequestHeader(value = Idempotency.HEADER, required = false) String key,
            @PathVariable UUID rideId, @Valid @RequestBody OpsCancelBody body) {
        Actor ops = new Actor(caller.roles().contains(UserRole.OPS) ? Actor.Type.OPS : Actor.Type.ADMIN,
                caller.userId().toString());
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key,
                "POST /v1/ops/rides/" + rideId + "/cancel", body),
                () -> ResponseEntity.ok(rides.cancelBySystem(rideId, ops, body.reason(), Optional.ofNullable(body.fee())
                        .map(fee -> new FeeRequest(fee.purpose(), fee.amountPaise())))));
    }

    record OpsCancelBody(@NotBlank @Size(max = 500) String reason, @Valid FeeBody fee) {
    }

    record FeeBody(@NotNull @Pattern(regexp = "CANCELLATION_FEE|NO_SHOW_FEE") String purpose,
            @NotNull @Min(1) Long amountPaise) {
    }
}

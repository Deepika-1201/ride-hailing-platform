package com.ridehailing.operations.web;

import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.IdempotentCall;
import com.ridehailing.ride.RideOperations;
import com.ridehailing.ride.RideOperations.FeeRequest;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/** Operations' commands on rides (LLD §7.4, T13). */
@ApiController
@AllowedRoles({UserRole.OPS, UserRole.ADMIN})
@RequestMapping("/v1/ops/rides/{rideId}")
class OpsRideController {

    private final RideOperations rides;
    private final Idempotency idempotency;

    OpsRideController(RideOperations rides, Idempotency idempotency) {
        this.rides = rides;
        this.idempotency = idempotency;
    }

    @PostMapping("/cancel")
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

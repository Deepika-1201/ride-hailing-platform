package com.ridehailing.operations.web;

import com.ridehailing.dispatch.AvailabilityStatus;
import com.ridehailing.dispatch.DispatchApi.DriverStatusView;
import com.ridehailing.dispatch.DispatchQueries;
import com.ridehailing.operations.app.DriverSuspensions;
import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Cursor;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.IdempotentCall;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.Page;
import com.ridehailing.shared.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Operations' drivers: the list (LLD §13.5), suspension and reinstatement (FR-D5, §8.8). */
@ApiController
@AllowedRoles({UserRole.OPS, UserRole.ADMIN})
@RequestMapping(OpsDriverController.PATH)
class OpsDriverController {

    static final String PATH = "/v1/ops/drivers";

    private final DriverSuspensions suspensions;
    private final DispatchQueries dispatch;
    private final Idempotency idempotency;

    OpsDriverController(DriverSuspensions suspensions, DispatchQueries dispatch, Idempotency idempotency) {
        this.suspensions = suspensions;
        this.dispatch = dispatch;
        this.idempotency = idempotency;
    }

    /** Drivers who have been online (LLD §13.5), by their latest status change. */
    @GetMapping
    Page<DriverStatusView> drivers(@RequestParam(name = "city_id", required = false) String cityId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        AvailabilityStatus wanted = null;
        if (status != null) {
            try {
                wanted = AvailabilityStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw ApiException.invalid("status", "must be an availability status such as AVAILABLE");
            }
        }
        return dispatch.drivers(cityId, wanted, Cursor.decode(cursor), Cursor.limit(limit));
    }

    @PostMapping("/{driverId}/suspend")
    ResponseEntity<?> suspend(Caller caller, @RequestHeader(value = Idempotency.HEADER, required = false) String key,
            @PathVariable UUID driverId, @Valid @RequestBody ReasonBody body) {
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key,
                "POST " + PATH + "/" + driverId + "/suspend", body),
                () -> ResponseEntity.ok(suspensions.suspend(driverId, staff(caller), body.reason())));
    }

    @PostMapping("/{driverId}/reinstate")
    ResponseEntity<?> reinstate(Caller caller, @RequestHeader(value = Idempotency.HEADER, required = false) String key,
            @PathVariable UUID driverId, @Valid @RequestBody ReasonBody body) {
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key,
                "POST " + PATH + "/" + driverId + "/reinstate", body),
                () -> ResponseEntity.ok(suspensions.reinstate(driverId, staff(caller), body.reason())));
    }

    private static Actor staff(Caller caller) {
        return new Actor(caller.has(UserRole.OPS) ? Actor.Type.OPS : Actor.Type.ADMIN, caller.userId().toString());
    }

    record ReasonBody(@NotBlank @Size(max = 500) String reason) {
    }
}

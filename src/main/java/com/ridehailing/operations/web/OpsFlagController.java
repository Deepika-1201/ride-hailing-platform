package com.ridehailing.operations.web;

import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Cursor;
import com.ridehailing.ride.RideOperations;
import com.ridehailing.ride.RideOperations.FlagFilter;
import com.ridehailing.ride.RideOperations.FlagView;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.Page;
import com.ridehailing.shared.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The review queue (LLD §7.11, §13.5). */
@ApiController
@AllowedRoles({UserRole.OPS, UserRole.ADMIN})
@RequestMapping("/v1/ops/flags")
class OpsFlagController {

    /** The FlagKind enum of {@code openapi.yaml}; {@code OFFLINE_CONFLICT} is raised from V2. */
    private static final Set<String> KINDS = Set.of("ARRIVED_FAR", "DRIVER_CANCELLED_AT_PICKUP", "PIN_LOCKED",
            "OFFLINE_CONFLICT", "STUCK");

    private final RideOperations rides;

    OpsFlagController(RideOperations rides) {
        this.rides = rides;
    }

    @GetMapping
    Page<FlagView> flags(@RequestParam(defaultValue = "true") boolean open,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        if (kind != null && !KINDS.contains(kind)) {
            throw ApiException.invalid("kind", "must be one of " + KINDS);
        }
        return rides.flags(new FlagFilter(open, kind), Cursor.decode(cursor), Cursor.limit(limit));
    }

    @PostMapping("/{flagId}/resolve")
    FlagView resolve(Caller caller, @PathVariable UUID flagId, @Valid @RequestBody ResolveBody body) {
        Actor staff = new Actor(caller.has(UserRole.OPS) ? Actor.Type.OPS : Actor.Type.ADMIN,
                caller.userId().toString());
        return rides.resolveFlag(flagId, staff, body.resolution());
    }

    record ResolveBody(@NotBlank @Size(max = 500) String resolution) {
    }
}

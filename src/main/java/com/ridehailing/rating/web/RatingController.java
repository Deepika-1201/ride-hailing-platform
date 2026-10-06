package com.ridehailing.rating.web;

import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.IdempotentCall;
import com.ridehailing.rating.app.Ratings;
import com.ridehailing.shared.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/** Rating the other party of a completed ride (FR-RT1, LLD §13.4). */
@ApiController
@AllowedRoles({UserRole.RIDER, UserRole.DRIVER})
@RequestMapping("/v1/rides/{rideId}/rating")
class RatingController {

    private final Ratings ratings;
    private final Idempotency idempotency;

    RatingController(Ratings ratings, Idempotency idempotency) {
        this.ratings = ratings;
        this.idempotency = idempotency;
    }

    @PostMapping
    ResponseEntity<?> rate(Caller caller, @RequestHeader(value = Idempotency.HEADER, required = false) String key,
            @PathVariable UUID rideId, @Valid @RequestBody RatingBody body) {
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key,
                "POST /v1/rides/" + rideId + "/rating", body),
                () -> ResponseEntity.status(HttpStatus.CREATED).body(ratings.rate(rideId, caller.userId(),
                        body.stars(), body.comment())));
    }

    record RatingBody(@NotNull @Min(1) @Max(5) Integer stars, @Size(max = 500) String comment) {
    }
}

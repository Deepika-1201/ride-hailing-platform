package com.ridehailing.ride.web;

import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.IdempotentCall;
import com.ridehailing.ride.RideQueries;
import com.ridehailing.ride.RideView;
import com.ridehailing.ride.app.Booking;
import com.ridehailing.ride.app.Cancellations;
import com.ridehailing.ride.app.DriverCommands;
import com.ridehailing.ride.app.DriverCommands.StartOutcome;
import com.ridehailing.shared.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/** Booking, reading, the driver's commands and cancelling (LLD §7); each party sees its own view of a ride. */
@ApiController
@RequestMapping(RideController.RIDES)
class RideController {

    static final String RIDES = "/v1/rides";

    private final Booking booking;
    private final Cancellations cancellations;
    private final DriverCommands drivers;
    private final RideQueries queries;
    private final Idempotency idempotency;

    RideController(Booking booking, Cancellations cancellations, DriverCommands drivers, RideQueries queries,
            Idempotency idempotency) {
        this.booking = booking;
        this.cancellations = cancellations;
        this.drivers = drivers;
        this.queries = queries;
        this.idempotency = idempotency;
    }

    @PostMapping
    @AllowedRoles(UserRole.RIDER)
    ResponseEntity<?> book(Caller caller, @RequestHeader(value = Idempotency.HEADER, required = false) String key,
            @Valid @RequestBody BookRideBody body) {
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key, "POST " + RIDES, body), () -> {
            RideView ride = booking.book(caller.userId(), body.quoteId(), body.paymentMethodId()).forRider();
            return ResponseEntity.created(URI.create(RIDES + "/" + ride.id())).body(ride);
        });
    }

    @GetMapping("/{rideId}")
    @AllowedRoles({UserRole.RIDER, UserRole.DRIVER})
    RideView get(Caller caller, @PathVariable UUID rideId) {
        RideView ride = queries.find(rideId).orElseThrow(ApiException::notFound);
        if (ride.riderId().equals(caller.userId())) {
            return ride.forRider();
        }
        if (caller.userId().equals(ride.driverId())) {
            return ride.forDriver();
        }
        throw ApiException.notFound();
    }

    @PostMapping("/{rideId}/cancel")
    @AllowedRoles({UserRole.RIDER, UserRole.DRIVER})
    ResponseEntity<?> cancel(Caller caller, @RequestHeader(value = Idempotency.HEADER, required = false) String key,
            @PathVariable UUID rideId, @Valid @RequestBody(required = false) CancelRideBody body) {
        String reason = body == null ? null : body.reason();
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key,
                "POST " + RIDES + "/" + rideId + "/cancel", body == null ? Map.of() : body),
                () -> ResponseEntity.ok(cancellations.cancel(caller.userId(), rideId, reason)));
    }

    @PostMapping("/{rideId}/arrive")
    @AllowedRoles(UserRole.DRIVER)
    ResponseEntity<?> arrive(Caller caller, @RequestHeader(value = Idempotency.HEADER, required = false) String key,
            @PathVariable UUID rideId) {
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key,
                "POST " + RIDES + "/" + rideId + "/arrive", Map.of()),
                () -> ResponseEntity.ok(drivers.arrive(caller.userId(), rideId)));
    }

    /** A wrong PIN is a {@code 422} that commits its count, and is stored like any answer (LLD §7.6). */
    @PostMapping("/{rideId}/start")
    @AllowedRoles(UserRole.DRIVER)
    ResponseEntity<?> start(Caller caller, @RequestHeader(value = Idempotency.HEADER, required = false) String key,
            @PathVariable UUID rideId, @Valid @RequestBody StartTripBody body) {
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key,
                "POST " + RIDES + "/" + rideId + "/start", body), () -> {
                    StartOutcome outcome = drivers.start(caller.userId(), rideId, body.pin());
                    if (outcome.ride() != null) {
                        return ResponseEntity.ok(outcome.ride());
                    }
                    return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "WRONG_PIN",
                            "The PIN doesn't match; ask the rider for the one in their app.", null,
                            Map.of("attempts_left", outcome.attemptsLeft())).toResponse();
                });
    }

    @PostMapping("/{rideId}/complete")
    @AllowedRoles(UserRole.DRIVER)
    ResponseEntity<?> complete(Caller caller, @RequestHeader(value = Idempotency.HEADER, required = false) String key,
            @PathVariable UUID rideId, @Valid @RequestBody(required = false) CompleteTripBody body) {
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key,
                "POST " + RIDES + "/" + rideId + "/complete", body == null ? Map.of() : body),
                () -> ResponseEntity.ok(drivers.complete(caller.userId(), rideId)));
    }

    @PostMapping("/{rideId}/no-show")
    @AllowedRoles(UserRole.DRIVER)
    ResponseEntity<?> noShow(Caller caller, @RequestHeader(value = Idempotency.HEADER, required = false) String key,
            @PathVariable UUID rideId) {
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key,
                "POST " + RIDES + "/" + rideId + "/no-show", Map.of()),
                () -> ResponseEntity.ok(drivers.noShow(caller.userId(), rideId)));
    }

    record BookRideBody(@NotNull UUID quoteId, UUID paymentMethodId) {
    }

    record CancelRideBody(@Size(max = 200) String reason) {
    }

    /** {@code deviceTime} is for offline apps and is kept from V2 (LLD §7.10). */
    record StartTripBody(@NotNull @Pattern(regexp = "[0-9]{4}") String pin, Instant deviceTime) {
    }

    record CompleteTripBody(Instant deviceTime) {
    }
}

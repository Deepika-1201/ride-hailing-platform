package com.ridehailing.dispatch.web;

import com.ridehailing.dispatch.AvailabilityStatus;
import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.dispatch.DispatchApi.DriverStatusView;
import com.ridehailing.dispatch.DispatchApi.OfferView;
import com.ridehailing.driver.DriverApi;
import com.ridehailing.driver.DriverApi.DriverProfile;
import com.ridehailing.driver.DriverApi.Vehicle;
import com.ridehailing.driver.Verification;
import com.ridehailing.location.LiveIndex.LocationUpdate;
import com.ridehailing.location.LocationIngestion;
import com.ridehailing.location.LocationIngestion.BatchResult;
import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.IdempotentCall;
import com.ridehailing.platform.RateLimiter;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/** The signed-in driver: profile, availability and location (LLD §8.2, §9.6). Dispatch serves it (§13.3). */
@ApiController
@AllowedRoles(UserRole.DRIVER)
@RequestMapping(DriverMeController.PATH)
class DriverMeController {

    static final String PATH = "/v1/drivers/me";
    static final String LOCATION_LIMIT = "location-per-driver";

    private final DriverApi drivers;
    private final DispatchApi dispatch;
    private final LocationIngestion ingestion;
    private final Idempotency idempotency;
    private final RateLimiter rateLimiter;

    DriverMeController(DriverApi drivers, DispatchApi dispatch, LocationIngestion ingestion, Idempotency idempotency,
            RateLimiter rateLimiter) {
        this.drivers = drivers;
        this.dispatch = dispatch;
        this.ingestion = ingestion;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    DriverView me(Caller caller) {
        DriverProfile profile = drivers.profile(caller.userId()).orElseThrow(ApiException::notFound);
        return new DriverView(profile.id(), profile.firstName(), profile.lastName(), profile.cityId(),
                profile.verification(), profile.suspended(), profile.vehicles(), dispatch.status(caller.userId()));
    }

    @PostMapping("/online")
    ResponseEntity<?> goOnline(Caller caller, @RequestHeader(value = Idempotency.HEADER, required = false) String key,
            @Valid @RequestBody GoOnlineBody body) {
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key, "POST " + PATH + "/online",
                body), () -> ResponseEntity.ok(dispatch.goOnline(caller.userId(), body.vehicleId())));
    }

    @PostMapping("/offline")
    ResponseEntity<?> goOffline(Caller caller, @RequestHeader(value = Idempotency.HEADER, required = false) String key) {
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key, "POST " + PATH + "/offline",
                Map.of()), () -> ResponseEntity.ok(dispatch.goOffline(caller.userId())));
    }

    /** V1 apps poll this; reading it marks the offer seen (§8.5). */
    @GetMapping("/offer")
    ResponseEntity<OfferView> offer(Caller caller) {
        return dispatch.currentOffer(caller.userId()).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** A driver who isn't online has every update ignored, without touching the live index (§9.6). */
    @PostMapping("/location")
    BatchResult location(Caller caller, @Valid @RequestBody LocationBatchBody batch) {
        rateLimiter.acquireOrReject(LOCATION_LIMIT, caller.userId().toString());
        DriverStatusView status = dispatch.status(caller.userId());
        if (status.status() == AvailabilityStatus.OFFLINE) {
            return BatchResult.allIgnored(batch.updates().size());
        }
        return ingestion.accept(status.cityId(), status.category(), caller.userId(),
                batch.updates().stream().map(LocationUpdateBody::toUpdate).toList());
    }

    record DriverView(UUID id, String firstName, String lastName, String cityId, Verification verification,
            boolean suspended, List<Vehicle> vehicles, DriverStatusView status) {
    }

    record GoOnlineBody(@NotNull UUID vehicleId) {
    }

    record LocationBatchBody(@NotNull @Size(min = 1, max = 100) List<@NotNull @Valid LocationUpdateBody> updates) {
    }

    /** {@code replay} marks offline replays from V2; V1 treats every update alike. */
    record LocationUpdateBody(
            @NotNull @PositiveOrZero Long seq,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lon,
            @NotNull @PositiveOrZero Double accuracyM,
            @DecimalMin("0.0") @DecimalMax(value = "360.0", inclusive = false) Double headingDeg,
            @PositiveOrZero Double speedMps,
            @NotNull Instant deviceTime,
            Boolean replay) {

        LocationUpdate toUpdate() {
            return new LocationUpdate(seq, new GeoPoint(lat, lon), accuracyM, headingDeg, speedMps, deviceTime);
        }
    }
}

package com.ridehailing.rider.web;

import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Caller;
import com.ridehailing.rating.RatingApi;
import com.ridehailing.rating.RatingApi.Party;
import com.ridehailing.rating.RatingApi.RatingSummary;
import com.ridehailing.rider.app.RiderProfiles;
import com.ridehailing.rider.app.RiderProfiles.PaymentMethod;
import com.ridehailing.rider.db.RiderRepository.RiderRow;
import com.ridehailing.rider.db.SavedPlaceRepository.SavedPlace;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Page;
import com.ridehailing.shared.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/** The signed-in rider's own profile, places and payment methods (LLD §13.3). */
@ApiController
@AllowedRoles(UserRole.RIDER)
@RequestMapping("/v1/riders/me")
class RiderProfileController {

    private final RiderProfiles riders;
    private final RatingApi ratings;

    RiderProfileController(RiderProfiles riders, RatingApi ratings) {
        this.riders = riders;
        this.ratings = ratings;
    }

    @GetMapping
    RiderView profile(Caller caller) {
        return view(riders.profile(caller.userId()));
    }

    @PatchMapping
    RiderView update(Caller caller, @Valid @RequestBody RiderUpdate request) {
        if (request.firstName() == null && request.lastName() == null && request.email() == null) {
            throw ApiException.invalid("first_name", "give at least one of first_name, last_name and email");
        }
        return view(riders.update(caller.userId(), request.firstName(), request.lastName(), request.email()));
    }

    private RiderView view(RiderRow rider) {
        return new RiderView(rider.id(), rider.firstName(), rider.lastName(), rider.email(),
                ratings.summary(rider.id(), Party.RIDER), rider.defaultPaymentMethodId(), rider.createdAt());
    }

    @GetMapping("/places")
    Page<SavedPlace> places(Caller caller) {
        return new Page<>(riders.places(caller.userId()), null);
    }

    @PostMapping("/places")
    ResponseEntity<SavedPlace> addPlace(Caller caller, @Valid @RequestBody SavedPlaceCreate request) {
        SavedPlace place = riders.addPlace(caller.userId(), request.label(), request.name(), request.location());
        return ResponseEntity.status(HttpStatus.CREATED).body(place);
    }

    @DeleteMapping("/places/{placeId}")
    ResponseEntity<Void> deletePlace(Caller caller, @PathVariable UUID placeId) {
        riders.deletePlace(caller.userId(), placeId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/payment-methods")
    Page<PaymentMethod> paymentMethods(Caller caller) {
        return new Page<>(riders.paymentMethods(caller.userId()), null);
    }

    @PostMapping("/payment-methods")
    ResponseEntity<PaymentMethod> addPaymentMethod(Caller caller,
            @Valid @RequestBody PaymentMethodCreate request) {
        PaymentMethod method = riders.addPaymentMethod(caller.userId(), request.type(), request.providerToken(),
                request.display());
        return ResponseEntity.status(HttpStatus.CREATED).body(method);
    }

    @DeleteMapping("/payment-methods/{methodId}")
    ResponseEntity<Void> removePaymentMethod(Caller caller, @PathVariable UUID methodId) {
        riders.removePaymentMethod(caller.userId(), methodId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payment-methods/{methodId}/default")
    PaymentMethod setDefaultPaymentMethod(Caller caller, @PathVariable UUID methodId) {
        return riders.setDefaultPaymentMethod(caller.userId(), methodId);
    }

    /** The Rider schema of {@code openapi.yaml}. */
    record RiderView(UUID id, String firstName, String lastName, String email, RatingSummary rating,
            UUID defaultPaymentMethodId, Instant createdAt) {
    }

    record RiderUpdate(
            @Size(min = 1, max = 60) String firstName,
            @Size(max = 60) String lastName,
            @Email @Size(max = 200) String email) {
    }

    record SavedPlaceCreate(
            @NotNull @Size(min = 1, max = 40) String label,
            @NotNull @Size(min = 1, max = 200) String name,
            @NotNull @Valid GeoPoint location) {
    }

    record PaymentMethodCreate(
            @NotNull @Pattern(regexp = "CARD|UPI") String type,
            @NotNull @Size(min = 1, max = 100) String providerToken,
            @NotNull @Size(min = 1, max = 40) String display) {
    }
}

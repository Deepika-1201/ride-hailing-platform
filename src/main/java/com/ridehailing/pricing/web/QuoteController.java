package com.ridehailing.pricing.web;

import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.Caller;
import com.ridehailing.pricing.PricingApi;
import com.ridehailing.pricing.PricingApi.QuoteRequest;
import com.ridehailing.pricing.PricingApi.QuoteView;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/** Upfront fares (LLD §10.4); no idempotency key, since a repeat is just another quote (§5.1). */
@ApiController
@AllowedRoles(UserRole.RIDER)
@RequestMapping("/v1/quotes")
class QuoteController {

    private final PricingApi pricing;

    QuoteController(PricingApi pricing) {
        this.pricing = pricing;
    }

    @PostMapping
    ResponseEntity<QuoteView> quote(Caller caller, @Valid @RequestBody QuoteBody request) {
        QuoteView quote = pricing.quote(caller.userId(),
                new QuoteRequest(request.pickup(), request.dropoff(), request.category()));
        return ResponseEntity.status(HttpStatus.CREATED).body(quote);
    }

    record QuoteBody(
            @NotNull @Valid GeoPoint pickup,
            @NotNull @Valid GeoPoint dropoff,
            @NotNull @Pattern(regexp = "[A-Z][A-Z0-9_]{1,19}") String category) {
    }
}

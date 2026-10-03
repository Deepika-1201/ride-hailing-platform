package com.ridehailing.dispatch.web;

import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.IdempotentCall;
import com.ridehailing.shared.UserRole;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/** A driver's answer to an offer (LLD §8.4, §8.7). */
@ApiController
@AllowedRoles(UserRole.DRIVER)
@RequestMapping("/v1/offers/{offerId}")
class OfferController {

    private final DispatchApi dispatch;
    private final Idempotency idempotency;

    OfferController(DispatchApi dispatch, Idempotency idempotency) {
        this.dispatch = dispatch;
        this.idempotency = idempotency;
    }

    @PostMapping("/accept")
    ResponseEntity<?> accept(Caller caller, @RequestHeader(value = Idempotency.HEADER, required = false) String key,
            @PathVariable UUID offerId) {
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key,
                "POST /v1/offers/" + offerId + "/accept", Map.of()),
                () -> ResponseEntity.ok(dispatch.accept(offerId, caller.userId())));
    }

    @PostMapping("/decline")
    ResponseEntity<?> decline(Caller caller, @RequestHeader(value = Idempotency.HEADER, required = false) String key,
            @PathVariable UUID offerId) {
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key,
                "POST /v1/offers/" + offerId + "/decline", Map.of()),
                () -> ResponseEntity.ok(dispatch.decline(offerId, caller.userId())));
    }
}

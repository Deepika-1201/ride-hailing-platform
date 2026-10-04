package com.ridehailing.payment.web;

import com.ridehailing.payment.app.Dues;
import com.ridehailing.payment.app.Dues.DuesView;
import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.IdempotentCall;
import com.ridehailing.shared.UserRole;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/** The signed-in rider's dues (LLD §11.7). */
@ApiController
@AllowedRoles(UserRole.RIDER)
@RequestMapping(DuesController.PATH)
class DuesController {

    static final String PATH = "/v1/riders/me/dues";

    private final Dues dues;
    private final Idempotency idempotency;

    DuesController(Dues dues, Idempotency idempotency) {
        this.dues = dues;
        this.idempotency = idempotency;
    }

    @GetMapping
    DuesView dues(Caller caller) {
        return dues.of(caller.userId());
    }

    /** {@code 202}: the executor charges each due in the background. */
    @PostMapping("/pay")
    ResponseEntity<?> pay(Caller caller, @RequestHeader(value = Idempotency.HEADER, required = false) String key,
            @RequestBody(required = false) PayBody body) {
        UUID methodId = body == null ? null : body.paymentMethodId();
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key, "POST " + PATH + "/pay", body),
                () -> ResponseEntity.status(HttpStatus.ACCEPTED).body(dues.pay(caller.userId(), methodId)));
    }

    /** {@code paymentMethodId} null: the rider's default, which must not be cash. */
    record PayBody(UUID paymentMethodId) {
    }
}

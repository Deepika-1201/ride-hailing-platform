package com.ridehailing.operations.web;

import com.ridehailing.payment.PaymentApi;
import com.ridehailing.payment.PaymentApi.ChargeFilter;
import com.ridehailing.payment.PaymentApi.ChargeView;
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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Operations' payments list and refunds (LLD §11.6, §11.10), over {@code PaymentApi}. */
@ApiController
@AllowedRoles({UserRole.OPS, UserRole.ADMIN})
@RequestMapping("/v1/ops")
class OpsPaymentController {

    private static final Set<String> STATUSES = Set.of("PENDING", "SUCCEEDED", "FAILED", "UNKNOWN");

    private final PaymentApi payments;
    private final Idempotency idempotency;

    OpsPaymentController(PaymentApi payments, Idempotency idempotency) {
        this.payments = payments;
        this.idempotency = idempotency;
    }

    /** {@code older_than}: only charges unchanged for at least this long, such as {@code PT24H}. */
    @GetMapping("/payments")
    Page<ChargeView> charges(@RequestParam(required = false) String status,
            @RequestParam(name = "older_than", required = false) String olderThan,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.invalid("status", "must be one of " + STATUSES);
        }
        return payments.charges(new ChargeFilter(status, duration(olderThan)), Cursor.decode(cursor),
                Cursor.limit(limit));
    }

    @PostMapping("/charges/{chargeId}/refunds")
    ResponseEntity<?> refund(Caller caller, @RequestHeader(value = Idempotency.HEADER, required = false) String key,
            @PathVariable UUID chargeId, @Valid @RequestBody RefundBody body) {
        Actor ops = new Actor(caller.has(UserRole.OPS) ? Actor.Type.OPS : Actor.Type.ADMIN,
                caller.userId().toString());
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key,
                "POST /v1/ops/charges/" + chargeId + "/refunds", body),
                () -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(payments.refund(chargeId, body.amountPaise(), body.reason(), ops)));
    }

    private static Duration duration(String text) {
        if (text == null) {
            return null;
        }
        try {
            Duration duration = Duration.parse(text);
            if (!duration.isNegative()) {
                return duration;
            }
        } catch (DateTimeParseException e) {
            // answered below
        }
        throw ApiException.invalid("older_than", "must be an ISO-8601 duration such as PT24H");
    }

    record RefundBody(@NotNull @Min(1) Long amountPaise, @NotBlank @Size(max = 500) String reason) {
    }
}

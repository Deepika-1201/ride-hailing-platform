package com.ridehailing.payment.web;

import com.ridehailing.payment.app.Webhooks;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.PublicEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Payment provider webhooks (FR-PY6, LLD §11.4). Public: the provider signs each body, and the signature, not a token,
 * proves where it came from. The body is read as bytes, as signed, and one byte past the limit at most.
 */
@ApiController
@PublicEndpoint
@RequestMapping("/v1/webhooks/payments")
class WebhookController {

    private final Webhooks webhooks;

    WebhookController(Webhooks webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/{provider}")
    ResponseEntity<Void> receive(@PathVariable String provider,
            @RequestHeader(value = "X-Signature", required = false) String signature, HttpServletRequest request)
            throws IOException {
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(Webhooks.MAX_BODY_BYTES + 1);
        }
        webhooks.receive(provider, signature, body);
        return ResponseEntity.ok().build();
    }
}

package com.ridehailing.payment.app;

import com.ridehailing.payment.app.Outcomes.Result;
import com.ridehailing.payment.app.Outcomes.Source;
import com.ridehailing.payment.db.WebhookRepository;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Transactions;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Provider webhooks (FR-PY6, LLD §11.4, §11.10): verified, stored byte-exact, deduplicated by the provider's event ID,
 * and applied in the same transaction as the attempt's or refund's status allows.
 */
@Service
public class Webhooks {

    /** Bodies above this are refused unread beyond it (LLD §11.10). */
    public static final int MAX_BODY_BYTES = 64 * 1024;
    /** A failure reported without a code; a failed row always has one. */
    static final String UNSPECIFIED_FAILURE = "DECLINED";
    private static final Set<String> TYPES = Set.of("charge.succeeded", "charge.failed", "refund.succeeded",
            "refund.failed");
    private static final int MAX_EVENT_ID_LENGTH = 200;

    private final PaymentProvider provider;
    private final WebhookRepository webhooks;
    private final Outcomes outcomes;
    private final Transactions transactions;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration tolerance;

    Webhooks(PaymentProvider provider, WebhookRepository webhooks, Outcomes outcomes, Transactions transactions,
            JsonMapper json, Clock clock, PaymentProperties properties) {
        this.provider = provider;
        this.webhooks = webhooks;
        this.outcomes = outcomes;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
        this.tolerance = properties.webhookTolerance();
    }

    /**
     * Receives one webhook; a duplicate changes nothing.
     *
     * @throws ApiException {@code 404} for an unknown provider, {@code 401 WEBHOOK_SIGNATURE_INVALID}, or
     *     {@code 400 MALFORMED_REQUEST} for a signed body that isn't a payment webhook
     */
    public void receive(String providerName, String signature, byte[] body) {
        if (!provider.name().equals(providerName)) {
            throw ApiException.notFound();
        }
        if (body.length > MAX_BODY_BYTES) {
            throw malformed();
        }
        Instant signedAt = provider.verify(signature, body).orElseThrow(Webhooks::invalidSignature);
        if (Duration.between(signedAt, clock.instant()).abs().compareTo(tolerance) > 0) {
            throw invalidSignature();
        }
        String text = utf8(body);
        Event event = parse(text);
        transactions.run(() -> {
            if (!webhooks.insert(providerName, event.id(), event.type(), text)) {
                return;
            }
            ProviderAnswer answer = event.succeeded() ? ProviderAnswer.succeeded(event.reference())
                    : ProviderAnswer.failed(event.failureCode() == null ? UNSPECIFIED_FAILURE : event.failureCode());
            Result result = event.type().startsWith("charge.")
                    ? outcomes.attempt(event.key(), Source.WEBHOOK, answer)
                    : outcomes.refund(event.key(), Source.WEBHOOK, answer);
            webhooks.processed(providerName, event.id(), result.name());
        });
    }

    private Event parse(String text) {
        try {
            JsonNode root = json.readTree(text);
            JsonNode data = root.path("data");
            String id = root.path("id").asString("");
            String type = root.path("type").asString("");
            String status = data.path("status").asString("");
            long amount = data.path("amount_paise").asLong(0);
            Instant.parse(root.path("created_at").asString(""));
            UUID key = UUID.fromString(data.path("idempotency_key").asString(""));
            if (id.isBlank() || id.length() > MAX_EVENT_ID_LENGTH || !TYPES.contains(type)
                    || !type.endsWith("." + status) || amount < 1) {
                throw malformed();
            }
            return new Event(id, type, key, "succeeded".equals(status), text(data, "provider_reference"),
                    text(data, "failure_code"));
        } catch (JacksonException | DateTimeParseException | IllegalArgumentException e) {
            throw malformed();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String utf8(byte[] body) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException e) {
            throw malformed();
        }
    }

    private static ApiException invalidSignature() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID",
                "The webhook signature is missing, wrong or too old.");
    }

    private static ApiException malformed() {
        return new ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The body isn't a payment webhook.");
    }

    private record Event(String id, String type, UUID key, boolean succeeded, String reference, String failureCode) {
    }
}

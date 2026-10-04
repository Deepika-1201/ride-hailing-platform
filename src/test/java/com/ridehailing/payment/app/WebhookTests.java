package com.ridehailing.payment.app;

import static com.ridehailing.support.EventContract.outboxEvents;
import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static com.ridehailing.support.TestRides.asWorker;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.payment.db.AttemptRepository.AttemptRow;
import com.ridehailing.payment.db.ChargeRepository.ChargeRow;
import com.ridehailing.support.TestRides.AssignedRide;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Webhooks (FR-PY6, LLD §11.4, §11.10): before, after and instead of the response, duplicates, signatures, and the
 * raw body. The attempt's status decides, so each order ends the same way, with one success and one event.
 */
class WebhookTests extends PaymentTest {

    private static final String PATH = "/v1/webhooks/payments/{provider}";

    @Test
    void aWebhookBeforeTheResponseDecidesAndTheResponseChangesNothing() {
        AssignedRide ride = completed(riderWith("tok_ok"));
        ScriptedProvider scripted = new ScriptedProvider(mock);
        List<HttpResponse<String>> during = new ArrayList<>();
        scripted.duringCall = key -> during.add(postWebhook(mock.webhookBody(key).orElseThrow()));

        assertThat(asWorker(executorWith(scripted, properties)::sendNext)).isTrue();

        assertThat(during).singleElement().satisfies(response -> assertAnswered("POST", PATH, response, 200));
        ChargeRow charge = fare(ride);
        assertThat(charge.status()).isEqualTo("SUCCEEDED");
        assertThat(attempt(charge).status()).isEqualTo("SUCCEEDED");
        assertThat(webhookOutcomes(attempt(charge))).containsExactly("APPLIED");
        assertThat(outboxEvents(jdbc, "ChargeSucceeded", charge.id())).hasSize(1);
        assertThat(audits(charge, "charge.succeeded")).isEqualTo(1);
        assertThat(violations()).isEmpty();
    }

    @Test
    void aWebhookAfterTheResponseIsIgnored() {
        AssignedRide ride = completed(riderWith("tok_ok"));
        send();
        AttemptRow attempt = attempt(fare(ride));

        assertAnswered("POST", PATH, postWebhook(mock.webhookBody(attempt.id()).orElseThrow()), 200);

        assertThat(webhookOutcomes(attempt)).containsExactly("IGNORED");
        assertThat(outboxEvents(jdbc, "ChargeSucceeded", fare(ride).id())).hasSize(1);
    }

    @Test
    void aWebhookInsteadOfTheResponseResolvesTheUnknownAttempt() {
        AssignedRide paid = completed(riderWith("tok_timeout_succeeded"));
        AssignedRide declined = completed(riderWith("tok_timeout_failed"));
        send();
        AttemptRow succeeded = attempt(fare(paid));
        AttemptRow failed = attempt(fare(declined));
        assertThat(List.of(succeeded.status(), failed.status())).containsOnly("UNKNOWN");

        assertAnswered("POST", PATH, postWebhook(mock.webhookBody(succeeded.id()).orElseThrow()), 200);
        assertAnswered("POST", PATH, postWebhook(mock.webhookBody(failed.id()).orElseThrow()), 200);

        assertThat(fare(paid).status()).isEqualTo("SUCCEEDED");
        assertThat(fare(declined).status()).isEqualTo("FAILED");
        assertThat(fare(declined).failureCode()).isEqualTo("DECLINED");
        assertThat(attempt(fare(paid)).nextCheckAt()).as("no further checks").isNull();
        assertThat(check()).isZero();
        assertThat(mock.calls(succeeded.id()) + mock.calls(failed.id())).isEqualTo(2);
    }

    @Test
    void aDuplicateWebhookChangesNothing() {
        AssignedRide ride = completed(riderWith("tok_timeout_succeeded"));
        send();
        AttemptRow attempt = attempt(fare(ride));
        String body = mock.webhookBody(attempt.id()).orElseThrow();
        String sameEventOtherwise = webhook(eventIdOf(body), "charge.failed", attempt.id(), 1, "DECLINED");

        assertAnswered("POST", PATH, postWebhook(body), 200);
        assertAnswered("POST", PATH, postWebhook(body), 200);
        assertAnswered("POST", PATH, postWebhook(sameEventOtherwise), 200);

        assertThat(webhookOutcomes(attempt)).containsExactly("APPLIED");
        assertThat(fare(ride).status()).isEqualTo("SUCCEEDED");
        assertThat(outboxEvents(jdbc, "ChargeSucceeded", fare(ride).id())).hasSize(1);
    }

    @Test
    void theBodyIsStoredExactlyAsSigned() {
        AssignedRide ride = completed(riderWith("tok_timeout_succeeded"));
        send();
        UUID key = attempt(fare(ride)).id();
        String eventId = "evt_" + UUID.randomUUID();
        String body = "{ \"id\":\"" + eventId + "\",\n  \"type\" : \"charge.succeeded\", \"created_at\": \""
                + Instant.now() + "\", \"data\": {\"idempotency_key\": \"" + key + "\", \"status\": \"succeeded\","
                + " \"amount_paise\": 1, \"note\": \"café ✓\"}  }\n";

        assertAnswered("POST", PATH, postWebhook(body), 200);

        assertThat(jdbc.sql("SELECT raw_body FROM payment.provider_webhooks WHERE provider_event_id = :id")
                .param("id", eventId).query(String.class).single()).isEqualTo(body);
        assertThat(fare(ride).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void aKeyThatMatchesNothingIsAcknowledged() {
        String eventId = "evt_" + UUID.randomUUID();

        assertAnswered("POST", PATH, postWebhook(webhook(eventId, "charge.succeeded", UUID.randomUUID(), 100, null)),
                200);
        assertAnswered("POST", PATH, postWebhook(webhook(eventId + "r", "refund.failed", UUID.randomUUID(), 100,
                null)), 200);

        assertThat(jdbc.sql("""
                        SELECT outcome FROM payment.provider_webhooks
                        WHERE provider_event_id IN (:a, :b) ORDER BY provider_event_id
                        """).param("a", eventId).param("b", eventId + "r").query(String.class).list())
                .containsExactly("UNMATCHED", "UNMATCHED");
    }

    @Test
    void anUnsignedStaleOrForgedWebhookIsRefusedAndNotStored() {
        String eventId = "evt_" + UUID.randomUUID();
        String body = webhook(eventId, "charge.succeeded", UUID.randomUUID(), 100, null);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Instant now = Instant.now();
        String otherBody = webhook(eventId, "charge.failed", UUID.randomUUID(), 100, "DECLINED");
        String forged = new WebhookSignatures("another-secret-of-at-least-32-bytes!!".getBytes(StandardCharsets.UTF_8))
                .sign(now, bytes);

        for (String signature : new String[] {null, "", "t=1,v1=abc", mock.sign(now, otherBody.getBytes(
                StandardCharsets.UTF_8)), forged, mock.sign(now.minusSeconds(301), bytes),
                mock.sign(now.plusSeconds(301), bytes)}) {
            assertProblem("POST", PATH, postWebhook(body, signature), 401, "WEBHOOK_SIGNATURE_INVALID");
        }
        assertThat(stored(eventId)).isZero();

        assertAnswered("POST", PATH, postWebhook(body, mock.sign(now.minusSeconds(240), bytes)), 200);
        assertThat(stored(eventId)).isOne();
    }

    @Test
    void aSignedBodyThatIsNotAPaymentWebhookIsABadRequest() {
        UUID key = UUID.randomUUID();
        List<String> bodies = List.of(
                "not json",
                "{}",
                webhook("", "charge.succeeded", key, 100, null),
                webhook("evt_" + key, "charge.refunded", key, 100, null),
                webhook("evt_" + key, "payout.succeeded", key, 100, null),
                webhook("evt_" + key, "charge.succeeded", key, 0, null),
                webhook("evt_" + key, "charge.succeeded", key, 100, null).replace("\"succeeded\", \"amount",
                        "\"failed\", \"amount"),
                webhook("evt_" + key, "charge.succeeded", key, 100, null).replace(key + "\", \"provider", "x\", "
                        + "\"provider"),
                "{\"id\": \"evt_x\", \"type\": \"charge.succeeded\", \"created_at\": \"yesterday\", \"data\": "
                        + "{\"idempotency_key\": \"" + key + "\", \"status\": \"succeeded\", \"amount_paise\": 1}}",
                "{\"id\": \"" + "x".repeat(201) + "\", \"type\": \"charge.succeeded\", \"created_at\": \""
                        + Instant.now() + "\", \"data\": {\"idempotency_key\": \"" + key
                        + "\", \"status\": \"succeeded\", \"amount_paise\": 1}}");

        for (String body : bodies) {
            assertProblem("POST", PATH, postWebhook(body), 400, "MALFORMED_REQUEST");
        }
        String large = webhook("evt_" + key, "charge.succeeded", key, 100, null).replace("}}",
                "}, \"padding\": \"" + "x".repeat(Webhooks.MAX_BODY_BYTES) + "\"}");
        assertProblem("POST", PATH, postWebhook(large), 400, "MALFORMED_REQUEST");
        assertThat(jdbc.sql("SELECT count(*) FROM payment.provider_webhooks WHERE raw_body LIKE :key")
                .param("key", "%" + key + "%").query(Long.class).single()).isZero();
    }

    @Test
    void aFailureWithoutACodeIsADeclineAndABodyAtTheLimitIsRead() {
        AssignedRide ride = completed(riderWith("tok_timeout_failed"));
        send();
        AttemptRow attempt = attempt(fare(ride));
        String body = webhook("evt_" + attempt.id(), "charge.failed", attempt.id(), 1, null);
        String padded = body.replace("}}", "}, \"padding\": \"\"}");
        padded = padded.replace("\"padding\": \"\"", "\"padding\": \""
                + "x".repeat(Webhooks.MAX_BODY_BYTES - padded.length()) + "\"");
        assertThat(padded.getBytes(StandardCharsets.UTF_8)).hasSize(Webhooks.MAX_BODY_BYTES);

        assertAnswered("POST", PATH, postWebhook(padded), 200);

        assertThat(fare(ride).status()).isEqualTo("FAILED");
        assertThat(fare(ride).failureCode()).isEqualTo("DECLINED");
    }

    @Test
    void anUnknownProviderIsNotFound() {
        String body = webhook("evt_" + UUID.randomUUID(), "charge.succeeded", UUID.randomUUID(), 100, null);

        assertProblem("POST", PATH, postJson("/v1/webhooks/payments/stripe", Map.of("X-Signature",
                mock.sign(Instant.now(), body.getBytes(StandardCharsets.UTF_8))), body), 404, "NOT_FOUND");
    }

    private List<String> webhookOutcomes(AttemptRow attempt) {
        return jdbc.sql("""
                        SELECT outcome FROM payment.provider_webhooks
                        WHERE raw_body LIKE :key ORDER BY received_at
                        """)
                .param("key", "%" + attempt.id() + "%")
                .query(String.class)
                .list();
    }

    private long stored(String eventId) {
        return jdbc.sql("SELECT count(*) FROM payment.provider_webhooks WHERE provider_event_id = :id")
                .param("id", eventId).query(Long.class).single();
    }

    private long audits(ChargeRow charge, String action) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_log WHERE entity_id = :id AND action = :action")
                .param("id", charge.id().toString()).param("action", action).query(Long.class).single();
    }

    private static String eventIdOf(String body) {
        return body.replaceAll("^\\{\"id\":\"([^\"]+)\".*$", "$1");
    }
}

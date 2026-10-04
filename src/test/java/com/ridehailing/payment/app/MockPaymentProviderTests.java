package com.ridehailing.payment.app;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ridehailing.payment.app.PaymentProperties.Mock;
import com.ridehailing.payment.app.PaymentProperties.Webhooks;
import com.ridehailing.payment.app.ProviderAnswer.Status;
import com.ridehailing.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** The mock provider on its own (LLD §11.9, §11.10): tokens, idempotency, refunds, rates, latency and signatures. */
class MockPaymentProviderTests {

    private static final String SECRET = "a-test-webhook-secret-of-32-bytes-or-more";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(30);
    private static final Money FARE = new Money(25_900, "INR");
    /** As {@code application.yml} configures it: snake_case, null fields omitted. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private final MockPaymentProvider mock = provider(Duration.ZERO, Duration.ZERO, 0, 0, SECRET, "test");

    @Test
    void eachTestTokenFixesTheOutcome() {
        UUID ok = UUID.randomUUID();
        UUID decline = UUID.randomUUID();
        UUID timeoutFailed = UUID.randomUUID();
        UUID timeoutSucceeded = UUID.randomUUID();
        UUID webhookOnly = UUID.randomUUID();

        assertThat(mock.charge(ok, FARE, "tok_ok")).isEqualTo(ProviderAnswer.succeeded("pay_" + ok));
        assertThat(mock.charge(decline, FARE, "tok_decline")).isEqualTo(ProviderAnswer.failed("DECLINED"));
        assertTimesOut(() -> mock.charge(timeoutFailed, FARE, "tok_timeout_failed"));
        assertTimesOut(() -> mock.charge(timeoutSucceeded, FARE, "tok_timeout_succeeded"));
        assertThat(mock.charge(webhookOnly, FARE, "tok_webhook_only").status()).isEqualTo(Status.PENDING);

        assertThat(mock.status(ok).status()).isEqualTo(Status.SUCCEEDED);
        assertThat(mock.status(decline)).isEqualTo(ProviderAnswer.failed("DECLINED"));
        assertThat(mock.status(timeoutFailed)).isEqualTo(ProviderAnswer.failed("DECLINED"));
        assertThat(mock.status(timeoutSucceeded)).isEqualTo(ProviderAnswer.succeeded("pay_" + timeoutSucceeded));
        assertThat(mock.status(webhookOnly).status()).as("no webhook sent").isEqualTo(Status.PENDING);
        assertThat(mock.status(UUID.randomUUID()).status()).isEqualTo(Status.NOT_FOUND);
    }

    @Test
    void aRepeatedKeyAnswersTheFirstOutcomeWithoutChargingAgain() {
        UUID key = UUID.randomUUID();

        ProviderAnswer first = mock.charge(key, FARE, "tok_ok");
        ProviderAnswer again = mock.charge(key, new Money(1, "INR"), "tok_decline");

        assertThat(again).isEqualTo(first);
        assertThat(mock.calls(key)).isEqualTo(2);
        assertThat(JSON.readTree(mock.webhookBody(key).orElseThrow()).get("data").get("amount_paise").asLong())
                .isEqualTo(25_900);
    }

    @Test
    void refundsSucceedOnlyForPaymentsTheProviderTook() {
        UUID paid = UUID.randomUUID();
        UUID declined = UUID.randomUUID();
        UUID undecided = UUID.randomUUID();
        mock.charge(paid, FARE, "tok_ok");
        mock.charge(declined, FARE, "tok_decline");
        mock.charge(undecided, FARE, "tok_webhook_only");
        UUID refund = UUID.randomUUID();

        assertThat(mock.refund(refund, paid, new Money(100, "INR"))).isEqualTo(ProviderAnswer.succeeded("re_" + refund));
        assertThat(mock.refund(refund, declined, new Money(100, "INR"))).as("idempotent per key")
                .isEqualTo(ProviderAnswer.succeeded("re_" + refund));
        for (UUID payment : List.of(declined, undecided, refund, UUID.randomUUID())) {
            assertThat(mock.refund(UUID.randomUUID(), payment, new Money(100, "INR")))
                    .isEqualTo(ProviderAnswer.failed("PAYMENT_NOT_FOUND"));
        }
        assertThat(mock.status(refund).status()).isEqualTo(Status.SUCCEEDED);
        assertThat(JSON.readTree(mock.webhookBody(refund).orElseThrow()).get("type").asString())
                .isEqualTo("refund.succeeded");
    }

    @Test
    void otherTokensDrawFromTheRates() {
        MockPaymentProvider declining = provider(Duration.ZERO, Duration.ZERO, 1, 0, SECRET, "test");
        MockPaymentProvider timingOut = provider(Duration.ZERO, Duration.ZERO, 0, 1, SECRET, "test");
        UUID key = UUID.randomUUID();

        assertThat(mock.charge(UUID.randomUUID(), FARE, "tok_visa").status()).isEqualTo(Status.SUCCEEDED);
        assertThat(declining.charge(UUID.randomUUID(), FARE, "tok_visa")).isEqualTo(ProviderAnswer.failed("DECLINED"));
        assertTimesOut(() -> timingOut.charge(key, FARE, "tok_visa"));
        assertThat(timingOut.status(key).status()).isIn(Status.SUCCEEDED, Status.FAILED);
        assertThat(declining.charge(UUID.randomUUID(), FARE, "tok_ok").status()).as("test tokens ignore the rates")
                .isEqualTo(Status.SUCCEEDED);
    }

    @Test
    void callsTakeTheirLatencyAndTimeOutBeyondTheReadTimeout() {
        MockPaymentProvider slow = provider(Duration.ofMillis(20), Duration.ofMillis(20), 0, 0, SECRET, "test");
        MockPaymentProvider tooSlow = provider(Duration.ofMillis(40), Duration.ofMillis(40), 0, 0, SECRET, "test");

        long started = System.nanoTime();
        assertThat(slow.charge(UUID.randomUUID(), FARE, "tok_ok").status()).isEqualTo(Status.SUCCEEDED);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(20));
        assertTimesOut(() -> tooSlow.charge(UUID.randomUUID(), FARE, "tok_ok"));
        assertTimesOut(() -> tooSlow.status(UUID.randomUUID()));
        assertThatThrownBy(() -> provider(Duration.ofMillis(20), Duration.ofMillis(10), 0, 0, SECRET, "test"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("latency-p99");
    }

    @Test
    void theSecretIsLongEnoughAndGeneratedOnlyInDevelopment() {
        assertThatThrownBy(() -> provider(Duration.ZERO, Duration.ZERO, 0, 0, "x".repeat(31), "test"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least 32 bytes");
        assertThatThrownBy(() -> provider(Duration.ZERO, Duration.ZERO, 0, 0, null, "prod"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("required outside");

        MockPaymentProvider generated = provider(Duration.ZERO, Duration.ZERO, 0, 0, null, "local");
        byte[] body = "{}".getBytes(UTF_8);
        Instant at = Instant.ofEpochSecond(1_790_000_000);
        assertThat(generated.verify(generated.sign(at, body), body)).contains(at);
        assertThat(mock.verify(generated.sign(at, body), body)).as("another secret").isEmpty();
    }

    @Test
    void aSignatureCoversTheTimeAndTheExactBody() {
        byte[] body = "{\"id\": \"evt_1\"}".getBytes(UTF_8);
        Instant at = Instant.ofEpochSecond(1_790_000_000);
        String signature = mock.sign(at, body);

        assertThat(signature).matches("t=1790000000,v1=[0-9a-f]{64}");
        assertThat(mock.verify(signature, body)).contains(at);
        assertThat(mock.verify(signature, "{\"id\":\"evt_1\"}".getBytes(UTF_8))).isEmpty();
        assertThat(mock.verify(signature.replace("t=1790000000", "t=1790000001"), body)).isEmpty();
        assertThat(mock.verify(signature.toUpperCase(Locale.ROOT), body)).isEmpty();
        assertThat(mock.verify(signature + ",v0=00", body)).isEmpty();
        assertThat(mock.verify(null, body)).isEmpty();
    }

    @Test
    void aWebhookBodyFollowsThePaymentWebhookSchema() {
        UUID key = UUID.randomUUID();
        mock.charge(key, FARE, "tok_decline");

        JsonNode webhook = JSON.readTree(mock.webhookBody(key).orElseThrow());

        assertThat(webhook.get("id").asString()).startsWith("evt_");
        assertThat(webhook.get("type").asString()).isEqualTo("charge.failed");
        assertThat(Instant.parse(webhook.get("created_at").asString())).isNotNull();
        assertThat(webhook.get("data").get("idempotency_key").asString()).isEqualTo(key.toString());
        assertThat(webhook.get("data").get("status").asString()).isEqualTo("failed");
        assertThat(webhook.get("data").get("failure_code").asString()).isEqualTo("DECLINED");
        assertThat(webhook.get("data").has("provider_reference")).isFalse();
        assertThat(mock.webhookBody(UUID.randomUUID())).isEmpty();
        assertThat(JSON.readTree(mock.webhookBody(key).orElseThrow()).get("id")).as("duplicates share the ID")
                .isEqualTo(webhook.get("id"));
    }

    private static void assertTimesOut(Runnable call) {
        long started = System.nanoTime();
        assertThatThrownBy(call::run).isInstanceOf(ProviderException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(READ_TIMEOUT);
    }

    private static MockPaymentProvider provider(Duration median, Duration p99, double declineRate, double timeoutRate,
            String secret, String profile) {
        PaymentProperties properties = new PaymentProperties(2, Duration.ofMillis(250), Duration.ofSeconds(30),
                READ_TIMEOUT, List.of(Duration.ofSeconds(10)), Duration.ofHours(24), Duration.ofMinutes(2),
                Duration.ofMinutes(5), 5, Duration.ofSeconds(30), "INR",
                new Mock(median, p99, declineRate, timeoutRate, secret,
                        new Webhooks(false, null, Duration.ofSeconds(30), 0.1, 0.2)));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return new MockPaymentProvider(properties, environment, JSON);
    }
}

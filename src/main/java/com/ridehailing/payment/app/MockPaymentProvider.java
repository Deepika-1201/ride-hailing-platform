package com.ridehailing.payment.app;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.ridehailing.payment.app.PaymentProperties.Mock;
import com.ridehailing.platform.DevelopmentProfiles;
import com.ridehailing.shared.Money;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The in-process provider of LLD §11.9 and §11.10. It behaves like a remote one: latency, declines and timeouts;
 * idempotent per key; and signed webhooks for every outcome, posted over HTTP to the application, late, duplicated or
 * ahead of the answer. The deterministic test tokens fix the outcome; other tokens draw from the configured rates.
 */
@Component
@ConditionalOnProperty(prefix = "ride.payments", name = "provider", havingValue = "mock", matchIfMissing = true)
class MockPaymentProvider implements PaymentProvider, ApplicationListener<WebServerInitializedEvent>, AutoCloseable {

    static final String NAME = "mock";
    static final String DECLINED = "DECLINED";
    static final String PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND";

    private static final Logger log = LoggerFactory.getLogger(MockPaymentProvider.class);
    private static final double Z_99 = 2.326;
    private static final int MIN_SECRET_BYTES = 32;

    private final Map<UUID, Payment> payments = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Set<UUID> settled = ConcurrentHashMap.newKeySet();
    private final Duration readTimeout;
    private final Mock mock;
    private final WebhookSignatures signatures;
    private final MockWebhookSender webhooks;

    MockPaymentProvider(PaymentProperties properties, Environment environment, JsonMapper json) {
        this.readTimeout = properties.readTimeout();
        this.mock = properties.mock();
        if (mock.latencyMedian().isPositive() && mock.latencyP99().compareTo(mock.latencyMedian()) < 0) {
            throw new IllegalStateException("ride.payments.mock.latency-p99 must not be below latency-median");
        }
        this.signatures = new WebhookSignatures(secret(mock.webhookSecret(), DevelopmentProfiles.active(environment)));
        this.webhooks = new MockWebhookSender(mock.webhooks(), signatures, json, settled::add);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ProviderAnswer charge(UUID key, Money amount, String methodRef) {
        calls.computeIfAbsent(key, _ -> new AtomicInteger()).incrementAndGet();
        boolean[] first = {false};
        Payment payment = payments.computeIfAbsent(key, _ -> {
            first[0] = true;
            return decide(key, amount, methodRef);
        });
        return deliver(payment, first[0]);
    }

    @Override
    public ProviderAnswer refund(UUID key, UUID paymentKey, Money amount) {
        calls.computeIfAbsent(key, _ -> new AtomicInteger()).incrementAndGet();
        boolean[] first = {false};
        Payment refund = payments.computeIfAbsent(key, _ -> {
            first[0] = true;
            Payment paid = payments.get(paymentKey);
            boolean refundable = paid != null && paid.kind() == Kind.CHARGE && answer(paid).status()
                    == ProviderAnswer.Status.SUCCEEDED;
            return new Payment(key, Kind.REFUND, amount, refundable, refundable ? null : PAYMENT_NOT_FOUND,
                    "re_" + key, Delivery.ANSWERED);
        });
        return deliver(refund, first[0]);
    }

    @Override
    public ProviderAnswer status(UUID key) {
        simulateCall(latency());
        Payment payment = payments.get(key);
        return payment == null ? ProviderAnswer.notFound() : answer(payment);
    }

    @Override
    public Optional<Instant> verify(String signature, byte[] body) {
        return signatures.verify(signature, body);
    }

    /** How many charge or refund calls arrived with this key; for tests that show nothing is sent twice. */
    int calls(UUID key) {
        AtomicInteger count = calls.get(key);
        return count == null ? 0 : count.get();
    }

    /** Webhooks for the key that the application acknowledged, duplicates included; for tests. */
    int webhooksDelivered(UUID key) {
        return webhooks.delivered(key);
    }

    /** Signs a body as the provider would, for tests that post webhooks themselves. */
    String sign(Instant at, byte[] body) {
        return signatures.sign(at, body);
    }

    /** The webhook the provider would send for the key; empty if it never received the key. */
    Optional<String> webhookBody(UUID key) {
        return Optional.ofNullable(payments.get(key)).map(payment -> webhooks.body(payment.webhook()));
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (!"management".equals(event.getApplicationContext().getServerNamespace())) {
            webhooks.localPort(event.getWebServer().getPort());
        }
    }

    @Override
    public void close() {
        webhooks.close();
    }

    private Payment decide(UUID key, Money amount, String token) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return switch (token) {
            case "tok_ok" -> charge(key, amount, true, Delivery.ANSWERED);
            case "tok_decline" -> charge(key, amount, false, Delivery.ANSWERED);
            case "tok_timeout_failed" -> charge(key, amount, false, Delivery.TIMES_OUT);
            case "tok_timeout_succeeded" -> charge(key, amount, true, Delivery.TIMES_OUT);
            case "tok_webhook_only" -> charge(key, amount, true, Delivery.BY_WEBHOOK);
            default -> {
                double draw = random.nextDouble();
                if (draw < mock.declineRate()) {
                    yield charge(key, amount, false, Delivery.ANSWERED);
                }
                yield draw < mock.declineRate() + mock.timeoutRate()
                        ? charge(key, amount, random.nextBoolean(), Delivery.TIMES_OUT)
                        : charge(key, amount, true, Delivery.ANSWERED);
            }
        };
    }

    private static Payment charge(UUID key, Money amount, boolean succeeds, Delivery delivery) {
        return new Payment(key, Kind.CHARGE, amount, succeeds, succeeds ? null : DECLINED, "pay_" + key, delivery);
    }

    /** Webhooks go out on the first call only; the answer waits for the latency, or times out. */
    private ProviderAnswer deliver(Payment payment, boolean first) {
        if (first) {
            webhooks.schedule(payment.webhook());
        }
        if (payment.delivery() == Delivery.TIMES_OUT) {
            simulateCall(readTimeout.plusNanos(1));
        }
        simulateCall(latency());
        return answer(payment);
    }

    /** Waits as a caller would: the latency, or the read timeout and then no answer. */
    private void simulateCall(Duration latency) {
        if (latency.compareTo(readTimeout) > 0) {
            pause(readTimeout);
            throw new ProviderException("The mock provider didn't answer within " + readTimeout);
        }
        pause(latency);
    }

    /** A payment decided by webhook stays {@code PENDING} until its webhook has gone out. */
    private ProviderAnswer answer(Payment payment) {
        if (payment.delivery() == Delivery.BY_WEBHOOK && !settled.contains(payment.key())) {
            return ProviderAnswer.pending();
        }
        return payment.succeeds() ? ProviderAnswer.succeeded(payment.reference())
                : ProviderAnswer.failed(payment.failureCode());
    }

    /** Log-normal with the configured median and 99th percentile; zero when the median is zero. */
    private Duration latency() {
        long median = mock.latencyMedian().toNanos();
        if (median <= 0) {
            return Duration.ZERO;
        }
        double sigma = Math.log((double) mock.latencyP99().toNanos() / median) / Z_99;
        return Duration.ofNanos((long) (median * Math.exp(sigma * ThreadLocalRandom.current().nextGaussian())));
    }

    private static void pause(Duration duration) {
        if (!duration.isPositive()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Interrupted while waiting for the mock provider");
        }
    }

    private static byte[] secret(String configured, boolean development) {
        if (configured != null) {
            byte[] secret = configured.getBytes(UTF_8);
            if (secret.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException("ride.payments.mock.webhook-secret must be at least 32 bytes");
            }
            return secret;
        }
        if (!development) {
            throw new IllegalStateException(
                    "ride.payments.mock.webhook-secret is required outside the local and test profiles");
        }
        log.warn("Using a webhook secret generated in memory; processes that sign and verify webhooks must share one");
        byte[] secret = new byte[MIN_SECRET_BYTES];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    enum Kind {
        CHARGE,
        REFUND
    }

    /** Whether the call answers, times out, or answers {@code PENDING} and leaves the outcome to the webhook. */
    enum Delivery {
        ANSWERED,
        TIMES_OUT,
        BY_WEBHOOK
    }

    record Payment(UUID key, Kind kind, Money amount, boolean succeeds, String failureCode, String reference,
            Delivery delivery) {

        MockWebhookSender.Webhook webhook() {
            String type = kind.name().toLowerCase(Locale.ROOT) + (succeeds ? ".succeeded" : ".failed");
            return new MockWebhookSender.Webhook(key, "evt_" + UUID.nameUUIDFromBytes(key.toString().getBytes(UTF_8)),
                    type, succeeds ? reference : null, succeeds ? "succeeded" : "failed", amount.amountPaise(),
                    failureCode);
        }
    }
}

package com.ridehailing.payment.app;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.ridehailing.payment.app.PaymentProperties.Webhooks;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mock provider's webhooks (LLD §11.9): signed, posted 0 to {@code max-delay} late, some duplicated and some
 * before the API answers. A delivery that fails is retried after 1 s, 5 s and 30 s, as providers retry.
 */
final class MockWebhookSender implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MockWebhookSender.class);
    private static final List<Duration> RETRIES = List.of(Duration.ofSeconds(1), Duration.ofSeconds(5),
            Duration.ofSeconds(30));
    private static final String PATH = "/v1/webhooks/payments/" + MockPaymentProvider.NAME;

    private final Webhooks settings;
    private final WebhookSignatures signatures;
    private final JsonMapper json;
    private final Consumer<UUID> onFirstSend;
    private final ScheduledExecutorService scheduler;
    private final HttpClient http;
    private final Map<UUID, AtomicInteger> delivered = new ConcurrentHashMap<>();
    private volatile URI target;

    MockWebhookSender(Webhooks settings, WebhookSignatures signatures, JsonMapper json, Consumer<UUID> onFirstSend) {
        this.settings = settings;
        this.signatures = signatures;
        this.json = json;
        this.onFirstSend = onFirstSend;
        this.target = settings.url() == null ? null : URI.create(settings.url());
        this.scheduler = settings.enabled()
                ? Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("mock-webhooks").factory())
                : null;
        this.http = settings.enabled() ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build() : null;
    }

    /** Without a configured URL, webhooks go to this process's API port. */
    void localPort(int port) {
        if (settings.url() == null) {
            target = URI.create("http://localhost:" + port + PATH);
        }
    }

    /** Sends the webhook, perhaps twice, each copy now (before the answer) or later. */
    void schedule(Webhook webhook) {
        if (scheduler == null) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int copies = random.nextDouble() < settings.duplicateRate() ? 2 : 1;
        for (int copy = 0; copy < copies; copy++) {
            if (copy == 0 && random.nextDouble() < settings.earlyRate()) {
                post(webhook, 0);
            } else {
                later(webhook, Duration.ofMillis(random.nextLong(settings.maxDelay().toMillis() + 1)), 0);
            }
        }
    }

    String body(Webhook webhook) {
        return json.writeValueAsString(new Body(webhook.eventId(), webhook.type(), Instant.now(), new Data(
                webhook.key(), webhook.reference(), webhook.status(), webhook.amountPaise(), webhook.failureCode())));
    }

    private void later(Webhook webhook, Duration delay, int attempt) {
        scheduler.schedule(() -> Thread.startVirtualThread(() -> post(webhook, attempt)), delay.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void post(Webhook webhook, int attempt) {
        onFirstSend.accept(webhook.key());
        URI uri = target;
        if (uri == null) {
            log.warn("No webhook target yet; dropping {} for {}", webhook.type(), webhook.key());
            return;
        }
        byte[] body = body(webhook).getBytes(UTF_8);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Signature", signatures.sign(Instant.now(), body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 200) {
                delivered.computeIfAbsent(webhook.key(), _ -> new AtomicInteger()).incrementAndGet();
                return;
            }
            log.warn("Webhook {} for {} answered {}", webhook.type(), webhook.key(), status);
        } catch (IOException e) {
            log.warn("Webhook {} for {} failed: {}", webhook.type(), webhook.key(), e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (attempt < RETRIES.size()) {
            later(webhook, RETRIES.get(attempt), attempt + 1);
        }
    }

    /** Webhooks for the key that the application acknowledged, duplicates included. */
    int delivered(UUID key) {
        AtomicInteger count = delivered.get(key);
        return count == null ? 0 : count.get();
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** One webhook; duplicates share {@code eventId}, the provider's deduplication key. */
    record Webhook(UUID key, String eventId, String type, String reference, String status, long amountPaise,
            String failureCode) {
    }

    /** The {@code PaymentWebhook} schema of {@code openapi.yaml}. */
    record Body(String id, String type, Instant createdAt, Data data) {
    }

    record Data(UUID idempotencyKey, String providerReference, String status, long amountPaise, String failureCode) {
    }
}

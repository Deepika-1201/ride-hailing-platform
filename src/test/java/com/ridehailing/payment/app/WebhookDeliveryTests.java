package com.ridehailing.payment.app;

import static com.ridehailing.support.EventContract.outboxEvents;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.payment.db.AttemptRepository.AttemptRow;
import com.ridehailing.support.Eventually;
import com.ridehailing.support.TestRides.AssignedRide;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The mock's own webhooks (LLD §11.9), in a context of their own: posted over HTTP to the application, signed, each
 * twice. Only the webhook tells the outcome of a {@code tok_webhook_only} payment, and the duplicate changes nothing.
 */
@TestPropertySource(properties = {
    "ride.payments.mock.webhooks.enabled=true",
    "ride.payments.mock.webhooks.max-delay=0s",
    "ride.payments.mock.webhooks.duplicate-rate=1.0",
    "ride.payments.mock.webhooks.early-rate=0"
})
class WebhookDeliveryTests extends PaymentTest {

    @Test
    void theMocksWebhooksReachTheApplicationAndApplyOnce() {
        AssignedRide webhookOnly = completed(riderWith("tok_webhook_only"));
        AssignedRide declined = completed(riderWith("tok_decline"));

        send();

        AttemptRow pending = attempt(fare(webhookOnly));
        AttemptRow failed = attempt(fare(declined));
        Eventually.within(Duration.ofSeconds(10), () -> {
            assertThat(fare(webhookOnly).status()).isEqualTo("SUCCEEDED");
            assertThat(mock.webhooksDelivered(pending.id())).as("both copies").isEqualTo(2);
            assertThat(mock.webhooksDelivered(failed.id())).isEqualTo(2);
        });
        assertThat(webhooks(pending)).as("the duplicate deduplicated").isEqualTo(1);
        assertThat(webhooks(failed)).isEqualTo(1);
        assertThat(fare(declined).status()).isEqualTo("FAILED");
        assertThat(outboxEvents(jdbc, "ChargeSucceeded", fare(webhookOnly).id())).hasSize(1);
        assertThat(mock.status(pending.id()).status()).as("settled once its webhook went out")
                .isEqualTo(ProviderAnswer.Status.SUCCEEDED);
        assertThat(violations()).isEmpty();
    }

    private long webhooks(AttemptRow attempt) {
        return jdbc.sql("SELECT count(*) FROM payment.provider_webhooks WHERE raw_body LIKE :key")
                .param("key", "%" + attempt.id() + "%").query(Long.class).single();
    }
}

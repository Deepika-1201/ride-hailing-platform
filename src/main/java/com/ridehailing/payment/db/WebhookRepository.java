package com.ridehailing.payment.db;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Provider webhooks, stored byte-exact before processing and deduplicated by the provider's event ID (LLD §11.4). */
@Repository
public class WebhookRepository {

    private final JdbcClient jdbc;

    WebhookRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** False for a duplicate: the provider sent this event before. */
    public boolean insert(String provider, String eventId, String eventType, String rawBody) {
        return jdbc.sql("""
                        INSERT INTO payment.provider_webhooks (provider, provider_event_id, event_type, received_at,
                            raw_body)
                        VALUES (:provider, :eventId, :eventType, now(), :rawBody)
                        ON CONFLICT DO NOTHING
                        """)
                .param("provider", provider)
                .param("eventId", eventId)
                .param("eventType", eventType)
                .param("rawBody", rawBody)
                .update() == 1;
    }

    public void processed(String provider, String eventId, String outcome) {
        jdbc.sql("""
                        UPDATE payment.provider_webhooks SET processed_at = now(), outcome = :outcome
                        WHERE provider = :provider AND provider_event_id = :eventId
                        """)
                .param("provider", provider)
                .param("eventId", eventId)
                .param("outcome", outcome)
                .update();
    }
}

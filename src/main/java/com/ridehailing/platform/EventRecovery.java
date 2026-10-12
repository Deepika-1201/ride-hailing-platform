package com.ridehailing.platform;

import com.ridehailing.shared.Page;
import java.time.Instant;
import java.util.UUID;

public interface EventRecovery {

    Page<DeadLetterView> deadLetters(String consumer, Cursor cursor, int limit);

    DeadLetterView redrive(UUID id);

        ReplayView startReplay(String consumer, Instant from, Instant to, boolean reprocess);

        ReplayView replay(UUID id);

        ReplayView retryReplay(UUID id);

    record DeadLetterView(UUID id, String consumer, String topic, UUID eventId, String eventType, String error,
            Instant createdAt, Instant redrivenAt) {
    }

        record ReplayView(UUID id, String consumer, Instant from, Instant to, String status, long events, long totalEvents,
            boolean reprocess, String error) {
        }
}
package com.ridehailing.platform;

import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;

/** Runs API commands at most once per {@code Idempotency-Key} (ADR-009, LLD §5.1). */
public interface Idempotency {

    String HEADER = "Idempotency-Key";
    String REPLAYED_HEADER = "Idempotent-Replayed";

    /**
     * Runs {@code command} in one transaction with the key and stores its response, whatever its status; or replays
     * the stored response of an earlier call with the same key and request. If {@code command} throws, the key is
     * released with the rollback.
     *
     * @throws ApiException {@code 400} for a missing or malformed key, {@code 422} for a key reused with a different
     *     request, {@code 409} while another call with the key is still running
     */
    ResponseEntity<?> execute(IdempotentCall call, Supplier<? extends ResponseEntity<?>> command);
}

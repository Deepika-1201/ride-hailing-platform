package com.ridehailing.platform;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Where a page ended: the last row's {@code created_at} and ID, the order of every list (LLD §13.1). Clients see it as
 * an opaque string.
 */
public record Cursor(Instant createdAt, UUID id) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public String encode() {
        long micros = createdAt.getEpochSecond() * 1_000_000 + createdAt.getNano() / 1_000;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((micros + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** Null for the first page; a cursor this server didn't produce is {@code 400 VALIDATION_FAILED}. */
    public static Cursor decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split(":", 2);
            long micros = Long.parseLong(parts[0]);
            return new Cursor(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1_000L),
                    UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            throw ApiException.invalid("cursor", "is not a cursor from this API");
        }
    }

    /** The page size: {@link #DEFAULT_LIMIT} when absent, at most {@link #MAX_LIMIT}. */
    public static int limit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1 || requested > MAX_LIMIT) {
            throw ApiException.invalid("limit", "must be between 1 and " + MAX_LIMIT);
        }
        return requested;
    }
}

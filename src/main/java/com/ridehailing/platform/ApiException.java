package com.ridehailing.platform;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/** An error reported to API clients as a problem detail with a stable {@code code} (LLD §13.2). */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Duration retryAfter;
    private final Map<String, Object> properties;

    public ApiException(HttpStatus status, String code, String detail) {
        this(status, code, detail, null, Map.of());
    }

    /** {@code retryAfter}, if not null, becomes the {@code Retry-After} header; {@code properties} extend the problem. */
    public ApiException(HttpStatus status, String code, String detail, Duration retryAfter, Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
        this.properties = Map.copyOf(properties);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    public Map<String, Object> properties() {
        return properties;
    }
}

package com.ridehailing.platform;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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

    /** A resource that doesn't exist or that the caller may not see: both answer {@code 404} (LLD §12.4). */
    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
    }

    /** {@code 400 VALIDATION_FAILED} for one field, in the same shape as Bean Validation's errors. */
    public static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request is invalid.", null,
                Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }

    /** {@code 409 VERSION_CONFLICT}: the client's {@code version} is stale or missing; re-read and retry. */
    public static ApiException versionConflict(long currentVersion) {
        return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                "The resource changed since you read it.", null, Map.of("current_version", currentVersion));
    }

    public static ApiException alreadyExists(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "ALREADY_EXISTS", detail);
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

    /**
     * The problem response the error handler would send, for a command that answers a refusal instead of throwing it,
     * so that its transaction commits and the idempotency row stores the answer (LLD §7.6). Not for errors that
     * carry {@code Retry-After}.
     */
    public ResponseEntity<ProblemDetail> toResponse() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, getMessage());
        properties.forEach(problem::setProperty);
        problem.setProperty("code", code);
        String requestId = MDC.get(LogContext.REQUEST_ID);
        if (requestId != null) {
            problem.setProperty("request_id", requestId);
        }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request) {
            problem.setInstance(URI.create(request.getRequest().getRequestURI()));
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}

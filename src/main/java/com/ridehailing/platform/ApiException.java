package com.ridehailing.platform;

import org.springframework.http.HttpStatus;

/** An error reported to API clients as a problem detail with a stable {@code code} (LLD §13.2). */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}

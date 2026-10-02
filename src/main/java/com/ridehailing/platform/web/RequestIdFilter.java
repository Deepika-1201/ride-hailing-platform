package com.ridehailing.platform.web;

import com.ridehailing.platform.LogContext;
import com.ridehailing.platform.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an ID: echoed in {@code X-Request-Id} and logged as {@code request_id} (LLD §13.1). Requests
 * are the {@code api} role's entry point, so the role goes into the logging context too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class RequestIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Request-Id";

    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = acceptOrGenerate(request.getHeader(HEADER));
        response.setHeader(HEADER, requestId);
        MDC.put(LogContext.REQUEST_ID, requestId);
        MDC.put(LogContext.ROLE, Role.API.id());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(LogContext.REQUEST_ID);
            MDC.remove(LogContext.ROLE);
        }
    }

    static String acceptOrGenerate(String candidate) {
        if (candidate != null && ACCEPTED.matcher(candidate).matches()) {
            return candidate;
        }
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }
}

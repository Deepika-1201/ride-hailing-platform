package com.ridehailing.platform.web;

import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.PublicEndpoint;
import com.ridehailing.platform.security.Callers;
import com.ridehailing.shared.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces each API handler's declaration (LLD §12.4): {@code @PublicEndpoint} lets anyone in; otherwise the caller
 * needs a token ({@code 401}) and one of the {@code @AllowedRoles} ({@code 403}). A handler that declares neither
 * refuses everyone.
 */
final class AccessInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)
                || !AnnotatedElementUtils.hasAnnotation(method.getBeanType(), ApiController.class)) {
            return true;
        }
        Declaration declaration = declarationOf(method);
        if (declaration.isPublic()) {
            return true;
        }
        Caller caller = Callers.current().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED", "A valid access token is required."));
        if (Arrays.stream(declaration.roles()).noneMatch(caller::has)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Your role can't use this endpoint.");
        }
        return true;
    }

    /** The method's own declaration, else its class's; none means no role may call it. */
    private static Declaration declarationOf(HandlerMethod method) {
        if (method.hasMethodAnnotation(PublicEndpoint.class)) {
            return new Declaration(true, new UserRole[0]);
        }
        AllowedRoles roles = method.getMethodAnnotation(AllowedRoles.class);
        if (roles != null) {
            return new Declaration(false, roles.value());
        }
        if (AnnotatedElementUtils.hasAnnotation(method.getBeanType(), PublicEndpoint.class)) {
            return new Declaration(true, new UserRole[0]);
        }
        AllowedRoles classRoles = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), AllowedRoles.class);
        return new Declaration(false, classRoles == null ? new UserRole[0] : classRoles.value());
    }

    private record Declaration(boolean isPublic, UserRole[] roles) {
    }
}

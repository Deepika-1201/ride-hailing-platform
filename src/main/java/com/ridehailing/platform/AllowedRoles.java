package com.ridehailing.platform;

import com.ridehailing.shared.UserRole;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The user roles that may call an endpoint, on a handler method or its {@link ApiController} class; the method's
 * declaration wins. Without a token the answer is {@code 401}, with none of these roles {@code 403} (LLD §12.4).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AllowedRoles {

    UserRole[] value();
}

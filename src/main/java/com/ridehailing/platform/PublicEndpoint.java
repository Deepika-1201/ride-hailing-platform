package com.ridehailing.platform;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** An endpoint anyone may call without a token, such as sign-in; it protects itself with rate limits (LLD §12.4). */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublicEndpoint {
}

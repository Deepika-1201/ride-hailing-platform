package com.ridehailing.platform;

import com.ridehailing.platform.roles.OnRoleCondition;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/** Registers the annotated bean only when {@code ride.roles} includes the given role. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnRoleCondition.class)
public @interface ConditionalOnRole {

    Role value();
}

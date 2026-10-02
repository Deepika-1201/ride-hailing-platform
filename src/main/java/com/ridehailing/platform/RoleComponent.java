package com.ridehailing.platform;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Component;

/** A component that exists only in processes running the given role, such as a poller (LLD §1.3). */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
@ConditionalOnRole(Role.API)
public @interface RoleComponent {

    @AliasFor(annotation = ConditionalOnRole.class, attribute = "value")
    Role value();
}

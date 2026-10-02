package com.ridehailing.platform.roles;

import com.ridehailing.platform.ConditionalOnRole;
import com.ridehailing.platform.Role;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Backs {@link ConditionalOnRole}. */
public final class OnRoleCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Role required = metadata.getAnnotations().get(ConditionalOnRole.class).getEnum("value", Role.class);
        Set<Role> active = ActiveRoles.of(context.getEnvironment());
        ConditionMessage.Builder message = ConditionMessage.forCondition(ConditionalOnRole.class, required.id());
        return active.contains(required)
                ? ConditionOutcome.match(message.because("the role is active"))
                : ConditionOutcome.noMatch(message.because("the active roles are " + active));
    }
}

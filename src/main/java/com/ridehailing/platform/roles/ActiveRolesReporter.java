package com.ridehailing.platform.roles;

import com.ridehailing.platform.Role;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Reports this process's roles on {@code /actuator/info} and in the startup log. */
@Component
final class ActiveRolesReporter implements InfoContributor {

    private static final Logger log = LoggerFactory.getLogger(ActiveRolesReporter.class);

    private final List<String> roles;

    ActiveRolesReporter(RoleProperties properties) {
        this.roles = properties.roles().stream().sorted().map(Role::id).toList();
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("roles", roles);
    }

    @EventListener(ApplicationReadyEvent.class)
    void logRoles() {
        log.info("Started with roles {}", roles);
    }
}

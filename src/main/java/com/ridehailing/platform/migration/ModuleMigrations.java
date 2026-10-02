package com.ridehailing.platform.migration;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Migrates each module's schema with its own Flyway history ({@code <module>.flyway_schema_history}), so the history
 * moves with the module if it is ever extracted (ADR-019, LLD §4.9).
 */
@Configuration(proxyBeanMethods = false)
public class ModuleMigrations {

    /** Modules that own a schema, in migration order; {@code shared} and {@code operations} own no tables. */
    public static final List<String> SCHEMA_OWNERS = List.of(
            "platform", "audit", "notification", "identity", "rider", "driver", "geography", "rating", "location",
            "pricing", "payment", "ride", "dispatch");

    @Bean
    FlywayMigrationStrategy migrateEachModule() {
        return flyway -> SCHEMA_OWNERS.forEach(module -> Flyway.configure()
                .configuration(flyway.getConfiguration())
                .schemas(module)
                .defaultSchema(module)
                .locations("classpath:db/migration/" + module)
                .load()
                .migrate());
    }
}

package com.ridehailing.platform.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

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
    FlywayMigrationStrategy migrateEachModule(SeedProperties seed) {
        return flyway -> {
            SCHEMA_OWNERS.forEach(module -> forModule(flyway, module)
                    .locations("classpath:db/migration/" + module)
                    .load()
                    .migrate());
            if (seed.enabled()) {
                // Seeds keep a history of their own, so a seeded database can later run without them (LLD §4.9).
                SCHEMA_OWNERS.stream().filter(ModuleMigrations::hasSeeds).forEach(module -> forModule(flyway, module)
                        .locations("classpath:db/seed/" + module)
                        .table("flyway_seed_history")
                        .baselineOnMigrate(true)
                        .baselineVersion("0")
                        .load()
                        .migrate());
            }
        };
    }

    private static FluentConfiguration forModule(Flyway flyway, String module) {
        return Flyway.configure().configuration(flyway.getConfiguration()).schemas(module).defaultSchema(module);
    }

    private static boolean hasSeeds(String module) {
        try {
            return new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/seed/" + module + "/*.sql").length > 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code ride.seed.enabled}: load {@code db/seed/<module>}, as the local profile does. */
    @ConfigurationProperties(prefix = "ride.seed")
    record SeedProperties(@DefaultValue("false") boolean enabled) {
    }
}

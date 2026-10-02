package com.ridehailing.platform.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.support.IntegrationTest;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;

class ModuleMigrationsTests extends IntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void everySchemaOwnerHasItsSchemaAndItsOwnMigrationHistory() {
        for (String module : ModuleMigrations.SCHEMA_OWNERS) {
            Integer applied = jdbc.sql("SELECT count(*) FROM " + module + ".flyway_schema_history"
                            + " WHERE version = '1' AND success")
                    .query(Integer.class)
                    .single();
            String owner = jdbc.sql("SELECT obj_description(oid, 'pg_namespace') FROM pg_namespace WHERE nspname = ?")
                    .param(module)
                    .query(String.class)
                    .single();

            assertThat(applied).as(module).isEqualTo(1);
            assertThat(owner).as(module).startsWith("Owned by the " + module + " module");
        }
    }

    @Test
    void everyMigrationFolderBelongsToASchemaOwner() throws IOException {
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*/*.sql");
        Set<String> folders = Arrays.stream(scripts)
                .map(ModuleMigrationsTests::folderOf)
                .collect(Collectors.toSet());

        assertThat(folders).containsExactlyInAnyOrderElementsOf(ModuleMigrations.SCHEMA_OWNERS);
    }

    @Test
    void runsOnPostgresql18WithPostgisAvailable() {
        Integer version = jdbc.sql("SELECT current_setting('server_version_num')::int").query(Integer.class).single();
        String postgis = jdbc.sql("SELECT default_version FROM pg_available_extensions WHERE name = 'postgis'")
                .query(String.class)
                .single();

        assertThat(version / 10_000).isEqualTo(18);
        assertThat(postgis).startsWith("3.");
    }

    private static String folderOf(Resource script) {
        try {
            String[] segments = script.getURL().getPath().split("/");
            return segments[segments.length - 2];
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}

package com.ridehailing.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.migration.ModuleMigrations;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** No module names another module's schema, in its migrations or in the SQL of its code (ADR-019). */
class SchemaOwnershipTests {

    private static final Path SOURCES = Path.of("src/main/java/com/ridehailing");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    private static final Pattern SQL_KEYWORD =
            Pattern.compile("\\b(select|insert|update|delete|create|alter|drop|from|join|into)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_COMMENT = Pattern.compile("--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern QUALIFIED_NAME = Pattern.compile("\\b([a-z_][a-z0-9_]*)\\s*\\.\\s*[a-z_\"]");

    @Test
    void everyModuleUsesOnlyItsOwnSchema() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path module : directories(SOURCES)) {
            String owner = module.getFileName().toString();
            for (Path source : files(module, ".java")) {
                for (String sql : sqlLiterals(Files.readString(source))) {
                    foreignSchemas(owner, sql).forEach(schema -> violations.add(source + " names " + schema));
                }
            }
        }
        for (Path folder : directories(MIGRATIONS)) {
            String owner = folder.getFileName().toString();
            for (Path script : files(folder, ".sql")) {
                foreignSchemas(owner, Files.readString(script)).forEach(schema -> violations.add(script + " names " + schema));
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void detectsAQueryOnAnotherModulesSchemaButNotOtherDottedNames() {
        String source = "class Attempt {\n"
                + "    String lock = \"\"\"\n"
                + "        SELECT status FROM ride.rides WHERE id = ? FOR SHARE -- not dispatch.offers\n"
                + "        \"\"\";\n"
                + "    String auditAction = \"ride.cancel\";\n"
                + "}\n";

        List<String> sql = sqlLiterals(source);

        assertThat(sql).hasSize(1);
        assertThat(foreignSchemas("dispatch", sql.getFirst())).containsExactly("ride");
        assertThat(foreignSchemas("ride", sql.getFirst())).isEmpty();
        assertThat(foreignSchemas("operations", sql.getFirst())).containsExactly("ride");
    }

    static List<String> sqlLiterals(String javaSource) {
        List<String> literals = new ArrayList<>();
        Matcher blocks = TEXT_BLOCK.matcher(javaSource);
        while (blocks.find()) {
            literals.add(blocks.group(1));
        }
        Matcher strings = STRING.matcher(blocks.replaceAll(""));
        while (strings.find()) {
            literals.add(strings.group(1));
        }
        return literals.stream().filter(literal -> SQL_KEYWORD.matcher(literal).find()).toList();
    }

    static Set<String> foreignSchemas(String owner, String sql) {
        Set<String> foreign = new TreeSet<>();
        Matcher names = QUALIFIED_NAME.matcher(SQL_COMMENT.matcher(sql).replaceAll(" ").toLowerCase(Locale.ROOT));
        while (names.find()) {
            String schema = names.group(1);
            if (ModuleMigrations.SCHEMA_OWNERS.contains(schema) && !schema.equals(owner)) {
                foreign.add(schema);
            }
        }
        return foreign;
    }

    private static List<Path> directories(Path root) throws IOException {
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory).sorted().toList();
        }
    }

    private static List<Path> files(Path root, String extension) throws IOException {
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(path -> path.toString().endsWith(extension)).sorted().toList();
        }
    }
}

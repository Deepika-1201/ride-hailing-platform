package com.ridehailing.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the server's WebSocket messages against {@code docs/schemas/websocket/server-messages.v1.json} and records
 * their types (LLD §17.1). Pushes are relayed to sessions as published, so a push checked on the bus counts too.
 */
public final class WebSocketContract {

    private static final String BASE = "https://ride-hailing.example/schemas/";
    private static final Path DIRECTORY = Path.of("docs/schemas");
    private static final String SERVER_MESSAGES = "websocket/server-messages.v1.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder
                    .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
                    .schemas(WebSocketContract::read));
    private static final Set<String> CHECKED = ConcurrentHashMap.newKeySet();

    private WebSocketContract() {
    }

    /** Answers the message parsed, once it conforms. */
    public static JsonNode assertConforms(String message) {
        JsonNode parsed = JSON.readTree(message);
        assertThat(SCHEMAS.getSchema(SchemaLocation.of(BASE + SERVER_MESSAGES)).validate(parsed))
                .as("server message %s", message).isEmpty();
        CHECKED.add(parsed.get("type").asString());
        return parsed;
    }

    /** The type of every server message a test checked, in this JVM. */
    static Set<String> checked() {
        return Set.copyOf(CHECKED);
    }

    /** The type of every server message the schema allows. */
    static Set<String> documented() {
        JsonNode schema = JSON.readTree(read(BASE + SERVER_MESSAGES));
        return schema.get("oneOf").valueStream()
                .map(ref -> ref.get("$ref").asString().substring("#/$defs/".length()))
                .map(name -> schema.get("$defs").get(name).get("properties").get("type").get("const").asString())
                .collect(Collectors.toSet());
    }

    private static String read(String iri) {
        if (!iri.startsWith(BASE)) {
            return null;
        }
        try {
            return Files.readString(DIRECTORY.resolve(iri.substring(BASE.length())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

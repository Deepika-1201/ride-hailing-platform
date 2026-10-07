package com.ridehailing.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Checks responses against {@code docs/openapi.yaml} (LLD §13.3, §17.1): the status must be documented for the
 * operation, the documented required headers present, and the body valid against its schema, formats included.
 */
public final class OpenApiContract {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode SPEC = YAMLMapper.builder().build().readTree(Path.of("docs/openapi.yaml").toFile());
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(
                    SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));
    private static final Map<String, Schema> COMPILED = new ConcurrentHashMap<>();
    private static final Set<String> CHECKED = ConcurrentHashMap.newKeySet();

    private OpenApiContract() {
    }

    /** Asserts the status and the contract; returns the body, or null when there is none. */
    public static JsonNode assertAnswered(String method, String pathTemplate, HttpResponse<String> response,
            int status) {
        assertThat(response.statusCode()).as("%s %s: %s", method, pathTemplate, response.body()).isEqualTo(status);
        assertConforms(method, pathTemplate, response);
        CHECKED.add(method + " " + pathTemplate + " " + status);
        return response.body().isEmpty() ? null : JSON.readTree(response.body());
    }

    /** Asserts a problem response with the code, and the contract; returns the problem. */
    public static JsonNode assertProblem(String method, String pathTemplate, HttpResponse<String> response, int status,
            String code) {
        JsonNode problem = assertAnswered(method, pathTemplate, response, status);
        assertThat(problem.path("code").asString()).as(response.body()).isEqualTo(code);
        return problem;
    }

    /** {@code pathTemplate} as documented, such as {@code /v1/admin/cities/{city_id}}. */
    public static void assertConforms(String method, String pathTemplate, HttpResponse<String> response) {
        String where = method + " " + pathTemplate + " answered " + response.statusCode();
        JsonNode operation = SPEC.path("paths").path(pathTemplate).path(method.toLowerCase(Locale.ROOT));
        assertThat(operation.isMissingNode()).as("%s: the operation is documented", where).isFalse();
        JsonNode documented = resolve(operation.path("responses").path(Integer.toString(response.statusCode())));
        assertThat(documented.isMissingNode()).as("%s: the status is documented; body %s", where, response.body())
                .isFalse();

        documented.path("headers").properties().forEach(header -> {
            if (resolve(header.getValue()).path("required").asBoolean(false)) {
                assertThat(response.headers().firstValue(header.getKey()))
                        .as("%s: header %s", where, header.getKey()).isPresent();
            }
        });

        JsonNode content = documented.path("content");
        if (content.isMissingNode()) {
            assertThat(response.body()).as("%s: no body", where).isEmpty();
            return;
        }
        String mediaType = response.headers().firstValue("Content-Type").orElse("").split(";")[0].trim();
        JsonNode media = content.path(mediaType);
        assertThat(media.isMissingNode()).as("%s: content type %s is documented", where, mediaType).isFalse();
        Schema schema = COMPILED.computeIfAbsent(method + " " + pathTemplate + " " + response.statusCode() + " "
                + mediaType, key -> compile(media.path("schema")));
        List<Error> errors = schema.validate(JSON.readTree(response.body()));
        assertThat(errors).as("%s: body %s", where, response.body()).isEmpty();
    }

    // The response schema, with the document's components beside it so that its $refs resolve.
    private static Schema compile(JsonNode responseSchema) {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.set("components", SPEC.path("components"));
        schema.setAll((ObjectNode) responseSchema);
        return SCHEMAS.getSchema(schema);
    }

    /** {@code METHOD /path/{template} status} of every response a test checked in this JVM (LLD §17.1). */
    static Set<String> checked() {
        return Set.copyOf(CHECKED);
    }

    static JsonNode spec() {
        return SPEC;
    }

    private static JsonNode resolve(JsonNode node) {
        JsonNode resolved = node;
        while (resolved.has("$ref")) {
            resolved = SPEC.at(resolved.get("$ref").asString().substring(1));
        }
        return resolved;
    }
}

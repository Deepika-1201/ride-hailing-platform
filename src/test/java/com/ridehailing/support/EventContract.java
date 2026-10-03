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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Checks outbox events against {@code docs/schemas/events}: the envelope, then the payload of its type and version. */
public final class EventContract {

    private static final String BASE = "https://ride-hailing.example/schemas/events/";
    private static final Path DIRECTORY = Path.of("docs/schemas/events");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder
                    .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
                    .schemas(EventContract::read));

    private EventContract() {
    }

    /** The aggregate's events of the type in the outbox, oldest first, as their envelopes. */
    public static List<JsonNode> outboxEvents(JdbcClient jdbc, String eventType, UUID aggregateId) {
        return jdbc.sql("""
                        SELECT event_id, event_type, event_version, aggregate_type, aggregate_id, aggregate_version,
                               occurred_at, producer, correlation_id, causation_id, payload::text AS payload
                        FROM platform.outbox WHERE event_type = :eventType AND aggregate_id = :aggregateId ORDER BY id
                        """)
                .param("eventType", eventType)
                .param("aggregateId", aggregateId)
                .query((row, rowNumber) -> {
                    ObjectNode envelope = JSON.createObjectNode();
                    envelope.put("event_id", row.getObject("event_id", UUID.class).toString());
                    envelope.put("event_type", row.getString("event_type"));
                    envelope.put("event_version", row.getInt("event_version"));
                    envelope.put("aggregate_type", row.getString("aggregate_type"));
                    envelope.put("aggregate_id", row.getObject("aggregate_id", UUID.class).toString());
                    envelope.put("aggregate_version", row.getLong("aggregate_version"));
                    envelope.put("occurred_at", row.getObject("occurred_at", OffsetDateTime.class).toInstant().toString());
                    envelope.put("producer", row.getString("producer"));
                    envelope.put("correlation_id", row.getString("correlation_id"));
                    if (row.getString("causation_id") != null) {
                        envelope.put("causation_id", row.getString("causation_id"));
                    }
                    envelope.set("payload", JSON.readTree(row.getString("payload")));
                    return (JsonNode) envelope;
                })
                .list();
    }

    public static void assertConforms(JsonNode envelope) {
        assertThat(SCHEMAS.getSchema(SchemaLocation.of(BASE + "envelope.v1.json")).validate(envelope))
                .as("envelope %s", envelope).isEmpty();
        String payloadSchema = envelope.get("event_type").asString() + ".v" + envelope.get("event_version").asInt()
                + ".json";
        assertThat(SCHEMAS.getSchema(SchemaLocation.of(BASE + payloadSchema)).validate(envelope.get("payload")))
                .as("payload %s", envelope.get("payload")).isEmpty();
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

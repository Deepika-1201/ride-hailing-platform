package com.ridehailing.platform.outbox;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import tools.jackson.databind.json.JsonMapper;

class KafkaDeadLetterListener implements AcknowledgingMessageListener<byte[], byte[]> {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Map<String, String> topics;

    KafkaDeadLetterListener(JdbcClient jdbc, JsonMapper json, Map<String, String> topics) {
        this.jdbc = jdbc;
        this.json = json;
        this.topics = Map.copyOf(topics);
    }

    @Override
    public void onMessage(ConsumerRecord<byte[], byte[]> record, Acknowledgment acknowledgment) {
        Objects.requireNonNull(acknowledgment, "Manual Kafka acknowledgement is required");
        String consumer = topics.get(record.topic());
        if (consumer == null) {
            throw new IllegalArgumentException("Unregistered dead-letter topic " + record.topic());
        }
        List<StoredHeader> headers = new ArrayList<>();
        record.headers().forEach(header -> headers.add(new StoredHeader(header.key(), header.value())));
        String error = headerText(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        jdbc.sql("""
                INSERT INTO platform.dead_letters
                    (consumer, topic, kafka_partition, kafka_offset, event_id, error, record_key, record_value, record_headers)
                VALUES (:consumer, :topic, :partition, :offset, :event, :error, :key, :value, :headers)
                ON CONFLICT (topic, kafka_partition, kafka_offset) DO NOTHING
                """)
                .param("consumer", consumer).param("topic", record.topic()).param("partition", record.partition())
                .param("offset", record.offset()).param("event", eventId(record))
                .param("error", error == null ? "Kafka consumer delivery failed" : error.replace("\u0000", "\\u0000"))
                .param("key", record.key(), java.sql.Types.BINARY)
                .param("value", record.value(), java.sql.Types.BINARY)
                .param("headers", json.writeValueAsBytes(headers)).update();
        acknowledgment.acknowledge();
    }

    private static UUID eventId(ConsumerRecord<byte[], byte[]> record) {
        String value = headerText(record, "event_id");
        if (value == null) {
            return null;
        }
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equals(value) ? id : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String headerText(ConsumerRecord<byte[], byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    record StoredHeader(String key, byte[] value) {
    }
}
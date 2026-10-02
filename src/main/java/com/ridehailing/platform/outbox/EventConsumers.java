package com.ridehailing.platform.outbox;

import com.ridehailing.platform.EventConsumer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** The consumers in this process, by name and by event type; names must be unique because they key the inbox. */
@Component
class EventConsumers {

    private final Map<String, EventConsumer> byName = new HashMap<>();
    private final Map<String, List<EventConsumer>> byType = new HashMap<>();

    EventConsumers(ObjectProvider<EventConsumer> consumers) {
        consumers.stream().sorted(Comparator.comparing(EventConsumer::name)).forEach(consumer -> {
            if (byName.putIfAbsent(consumer.name(), consumer) != null) {
                throw new IllegalStateException("Two event consumers are named " + consumer.name());
            }
            consumer.eventTypes().forEach(type -> byType.computeIfAbsent(type, _ -> new ArrayList<>()).add(consumer));
        });
    }

    List<EventConsumer> subscribedTo(String eventType) {
        return byType.getOrDefault(eventType, List.of());
    }

    Optional<EventConsumer> named(String name) {
        return Optional.ofNullable(byName.get(name));
    }
}

package com.ridehailing.notification.app;

import com.ridehailing.notification.db.DeliveryRepository;
import com.ridehailing.notification.db.NotificationRepository;
import com.ridehailing.platform.EventEnvelope;
import com.ridehailing.shared.Ids;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Creates notifications for the consumers, each with a push delivery due at once (LLD §15.4). */
@Component
class Notifications {

    static final String PUSH = "PUSH";

    private final NotificationRepository notifications;
    private final DeliveryRepository deliveries;

    Notifications(NotificationRepository notifications, DeliveryRepository deliveries) {
        this.notifications = notifications;
        this.deliveries = deliveries;
    }

    /** Once per event, recipient and kind: a repeated event, even delivered concurrently, adds nothing (race 7). */
    void notify(EventEnvelope event, UUID recipientId, NotificationKind kind, UUID rideId, ObjectNode payload) {
        notifications.insert(Ids.newId(), recipientId, kind.name(), rideId, event.eventId(), payload.toString())
                .ifPresent(id -> deliveries.insert(Ids.newId(), id, PUSH));
    }

    static ObjectNode payload() {
        return JsonNodeFactory.instance.objectNode();
    }

    static UUID uuid(JsonNode event, String field) {
        return UUID.fromString(event.get(field).asString());
    }
}

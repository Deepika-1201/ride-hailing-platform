package com.ridehailing.notification.app;

import static com.ridehailing.notification.app.Notifications.payload;
import static com.ridehailing.notification.app.Notifications.uuid;

import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.EventEnvelope;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** {@code notification.payments} (LLD §15.2, §15.4): tells the rider how each charge ended. */
@Component
class PaymentNotifications implements EventConsumer {

    static final String NAME = "notification.payments";

    private final Notifications notifications;

    PaymentNotifications(Notifications notifications) {
        this.notifications = notifications;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("ChargeSucceeded", "ChargeFailed");
    }

    @Override
    public void handle(EventEnvelope event) {
        JsonNode charge = event.payload();
        UUID rideId = uuid(charge, "ride_id");
        ObjectNode payload = payload().put("ride_id", rideId.toString())
                .put("charge_id", charge.get("charge_id").asString())
                .put("purpose", charge.get("purpose").asString());
        payload.set("amount", charge.get("amount"));
        NotificationKind kind = switch (event.eventType()) {
            case "ChargeSucceeded" -> NotificationKind.PAYMENT_SUCCEEDED;
            case "ChargeFailed" -> {
                payload.put("failure_code", charge.get("failure_code").asString());
                yield NotificationKind.PAYMENT_FAILED;
            }
            default -> throw new IllegalArgumentException("Not a charge event: " + event.eventType());
        };
        notifications.notify(event, uuid(charge, "rider_id"), kind, rideId, payload);
    }
}

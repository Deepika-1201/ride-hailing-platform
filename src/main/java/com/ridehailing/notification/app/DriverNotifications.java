package com.ridehailing.notification.app;

import static com.ridehailing.notification.app.Notifications.payload;
import static com.ridehailing.notification.app.Notifications.uuid;

import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.EventEnvelope;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** {@code notification.drivers} (LLD §15.2, §15.4): tells a driver why the platform took them offline. */
@Component
class DriverNotifications implements EventConsumer {

    static final String NAME = "notification.drivers";

    /** The reasons the driver didn't choose; a suspension is told by {@code DriverSuspended}. */
    static final Set<String> TOLD = Set.of("SILENT", "UNREACHABLE", "UNRESPONSIVE");

    private final Notifications notifications;

    DriverNotifications(Notifications notifications) {
        this.notifications = notifications;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("DriverWentOffline", "DriverSuspended");
    }

    @Override
    public void handle(EventEnvelope event) {
        JsonNode driver = event.payload();
        String reason = driver.get("reason").asString();
        switch (event.eventType()) {
            case "DriverWentOffline" -> {
                if (TOLD.contains(reason)) {
                    notifications.notify(event, uuid(driver, "driver_id"), NotificationKind.DRIVER_WENT_OFFLINE,
                            null, payload().put("reason", reason));
                }
            }
            case "DriverSuspended" -> notifications.notify(event, uuid(driver, "driver_id"),
                    NotificationKind.DRIVER_SUSPENDED, null, payload().put("reason", reason));
            default -> throw new IllegalArgumentException("Not a driver event: " + event.eventType());
        }
    }
}

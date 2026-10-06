package com.ridehailing.notification.app;

import com.ridehailing.platform.Poller;
import com.ridehailing.platform.Role;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Sends due deliveries on every worker node (LLD §15.4). */
@Component
class NotificationDeliveries implements Poller {

    static final String NAME = "notification-deliveries";

    private final DeliveryExecutor executor;
    private final NotificationProperties properties;

    NotificationDeliveries(DeliveryExecutor executor, NotificationProperties properties) {
        this.executor = executor;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Role role() {
        return Role.WORKER;
    }

    @Override
    public int threads() {
        return properties.workers();
    }

    @Override
    public Duration interval() {
        return properties.pollInterval();
    }

    @Override
    public boolean poll() {
        return executor.sendNext();
    }
}

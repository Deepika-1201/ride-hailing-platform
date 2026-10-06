package com.ridehailing.notification.app;

import com.ridehailing.platform.Transactions;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code notification_deliveries_total{channel,outcome}} (LLD §16.1, §15.4): {@code SENT}, {@code FAILED} (to be
 * retried) and {@code DEAD}, counted after commit and registered at zero for pushes.
 */
@Component
class NotificationMetrics {

    static final String SENT = "SENT";
    static final String FAILED = "FAILED";
    static final String DEAD = "DEAD";

    private static final String DELIVERIES = "notification.deliveries";

    private final MeterRegistry meters;
    private final Transactions transactions;

    NotificationMetrics(MeterRegistry meters, Transactions transactions) {
        this.meters = meters;
        this.transactions = transactions;
        List.of(SENT, FAILED, DEAD).forEach(outcome -> counted(Notifications.PUSH, outcome));
    }

    void recorded(String channel, String outcome) {
        transactions.afterCommit(() -> counted(channel, outcome).increment());
    }

    private Counter counted(String channel, String outcome) {
        return meters.counter(DELIVERIES, "channel", channel, "outcome", outcome);
    }
}

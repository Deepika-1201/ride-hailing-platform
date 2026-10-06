package com.ridehailing.notification.app;

import com.ridehailing.notification.db.DeliveryRepository;
import com.ridehailing.notification.db.DeliveryRepository.Claimed;
import com.ridehailing.notification.push.NotificationProvider;
import com.ridehailing.notification.push.Push;
import com.ridehailing.platform.Transactions;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends due deliveries (LLD §15.4): claim in one transaction, send outside any, record the answer in another. A crash
 * after a send sends again once the lease passes; notifications tolerate a duplicate push.
 */
@Component
class DeliveryExecutor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryExecutor.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final DeliveryRepository deliveries;
    private final NotificationProvider provider;
    private final NotificationMetrics metrics;
    private final Transactions transactions;
    private final NotificationProperties properties;
    private final JsonMapper json;

    DeliveryExecutor(DeliveryRepository deliveries, NotificationProvider provider, NotificationMetrics metrics,
            Transactions transactions, NotificationProperties properties, JsonMapper json) {
        this.deliveries = deliveries;
        this.provider = provider;
        this.metrics = metrics;
        this.transactions = transactions;
        this.properties = properties;
        this.json = json;
    }

    /** Sends the oldest due delivery; false if none is due. */
    boolean sendNext() {
        Optional<Claimed> claim = transactions.execute(() -> deliveries.claimDue(properties.lease()));
        if (claim.isEmpty()) {
            return false;
        }
        Claimed delivery = claim.get();
        try {
            provider.send(new Push(delivery.id(), delivery.recipientId(), delivery.kind(), delivery.rideId(),
                    json.readTree(delivery.payload())));
        } catch (RuntimeException e) {
            failed(delivery, e);
            return true;
        }
        transactions.run(() -> {
            if (deliveries.sent(delivery.id())) {
                metrics.recorded(delivery.channel(), NotificationMetrics.SENT);
            }
        });
        return true;
    }

    /** Waits {@code backoff[n - 1]} after failed attempt {@code n}; the failure after the last wait is final. */
    private void failed(Claimed delivery, RuntimeException e) {
        String error = error(e);
        int attempt = delivery.attempts();
        boolean dead = attempt > properties.backoff().size();
        transactions.run(() -> {
            if (dead) {
                if (deliveries.dead(delivery.id(), attempt, error)) {
                    metrics.recorded(delivery.channel(), NotificationMetrics.DEAD);
                }
            } else {
                Duration wait = properties.backoff().get(attempt - 1);
                if (deliveries.retryAfter(delivery.id(), attempt, error, wait)) {
                    metrics.recorded(delivery.channel(), NotificationMetrics.FAILED);
                }
            }
        });
        if (dead) {
            log.warn("Delivery {} of a {} notification is dead after {} attempts: {}", delivery.id(),
                    delivery.kind(), attempt, error);
        } else {
            log.info("Delivery {} of a {} notification failed, attempt {}: {}", delivery.id(), delivery.kind(),
                    attempt, error);
        }
    }

    private static String error(RuntimeException e) {
        String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
    }
}

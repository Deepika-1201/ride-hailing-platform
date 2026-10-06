package com.ridehailing.notification.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** The V1 mock: logs the push's kind and recipient, never its payload. */
@Component
class LoggingNotificationProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationProvider.class);

    @Override
    public void send(Push push) {
        log.info("Push {} to {} (mock: not delivered)", push.kind(), push.recipientId());
    }
}

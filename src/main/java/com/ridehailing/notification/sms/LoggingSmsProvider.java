package com.ridehailing.notification.sms;

import com.ridehailing.shared.Phones;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** The V1 mock: logs that a message went to a masked number, never its text, which may hold a code (LLD §12.1). */
@Component
class LoggingSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsProvider.class);

    @Override
    public void send(String phone, String text) {
        log.info("SMS of {} characters to {} (mock: not delivered)", text.length(), Phones.mask(phone));
    }
}

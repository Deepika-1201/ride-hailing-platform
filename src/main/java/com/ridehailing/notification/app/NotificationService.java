package com.ridehailing.notification.app;

import com.ridehailing.notification.NotificationApi;
import com.ridehailing.notification.sms.SmsProvider;
import org.springframework.stereotype.Service;

@Service
class NotificationService implements NotificationApi {

    private final SmsProvider sms;

    NotificationService(SmsProvider sms) {
        this.sms = sms;
    }

    @Override
    public void sendOneTimeCode(String phone, String code) {
        sms.send(phone, "Your ride-hailing sign-in code is " + code + ". Don't share it with anyone.");
    }
}

package com.ridehailing.notification.app;

import com.ridehailing.notification.NotificationApi;
import com.ridehailing.notification.db.NotificationRepository;
import com.ridehailing.notification.sms.SmsProvider;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class NotificationService implements NotificationApi {

    private final SmsProvider sms;
    private final NotificationRepository notifications;

    NotificationService(SmsProvider sms, NotificationRepository notifications) {
        this.sms = sms;
        this.notifications = notifications;
    }

    @Override
    public void sendOneTimeCode(String phone, String code) {
        sms.send(phone, "Your ride-hailing sign-in code is " + code + ". Don't share it with anyone.");
    }

    @Override
    public List<NotificationView> ofRide(UUID rideId) {
        return notifications.ofRide(rideId);
    }
}

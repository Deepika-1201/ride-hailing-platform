package com.ridehailing.notification.app;

import com.ridehailing.notification.NotificationApi;
import com.ridehailing.notification.db.NotificationRepository;
import com.ridehailing.notification.sms.SmsProvider;
import com.ridehailing.platform.Transactions;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class NotificationService implements NotificationApi {

    private final SmsProvider sms;
    private final NotificationRepository notificationRows;
    private final Notifications notifications;
    private final Transactions transactions;

    NotificationService(SmsProvider sms, NotificationRepository notificationRows, Notifications notifications,
            Transactions transactions) {
        this.sms = sms;
        this.notificationRows = notificationRows;
        this.notifications = notifications;
        this.transactions = transactions;
    }

    @Override
    public void sendOneTimeCode(String phone, String code) {
        sms.send(phone, "Your ride-hailing sign-in code is " + code + ". Don't share it with anyone.");
    }

    @Override
    public List<NotificationView> ofRide(UUID rideId) {
        return notificationRows.ofRide(rideId);
    }

    /** Its key comes from the ride, so the table's uniqueness lets one through across nodes (LLD §9.7). */
    @Override
    public void driverArriving(UUID rideId, UUID riderId) {
        UUID key = UUID.nameUUIDFromBytes((NotificationKind.DRIVER_ARRIVING + ":" + rideId)
                .getBytes(StandardCharsets.UTF_8));
        transactions.run(() -> notifications.notify(key, riderId, NotificationKind.DRIVER_ARRIVING, rideId,
                Notifications.payload().put("ride_id", rideId.toString())));
    }
}

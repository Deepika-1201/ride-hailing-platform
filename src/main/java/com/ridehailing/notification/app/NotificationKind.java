package com.ridehailing.notification.app;

/** What a notification tells its recipient (LLD §15.4, FR-N1). */
public enum NotificationKind {
    DRIVER_ASSIGNED,
    DRIVER_ARRIVING,
    DRIVER_UNASSIGNED,
    DRIVER_ARRIVED,
    TRIP_STARTED,
    TRIP_COMPLETED,
    RIDE_CANCELLED,
    NO_DRIVER_FOUND,
    PAYMENT_SUCCEEDED,
    PAYMENT_FAILED,
    DRIVER_WENT_OFFLINE,
    DRIVER_SUSPENDED
}

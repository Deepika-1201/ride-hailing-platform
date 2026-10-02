package com.ridehailing.notification;

/** Messages to users (LLD §15.4). */
public interface NotificationApi {

    /** Sends a sign-in code by SMS, synchronously; the code is never stored or queued (LLD §12.1). */
    void sendOneTimeCode(String phone, String code);
}

package com.ridehailing.notification.sms;

/** Delivers a text message; V1 has only a mock (LLD §15.4). */
public interface SmsProvider {

    void send(String phone, String text);
}

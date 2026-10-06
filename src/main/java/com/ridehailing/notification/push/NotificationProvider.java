package com.ridehailing.notification.push;

/** Delivers pushes to users' apps; V1 has only a mock (LLD §15.4). An exception means the push wasn't delivered. */
public interface NotificationProvider {

    void send(Push push);
}

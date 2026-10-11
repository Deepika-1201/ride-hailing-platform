package com.ridehailing.platform.outbox;

@FunctionalInterface
interface OutboxTransport {

    void publish(OutboxRows.Row row);
}
package com.ridehailing.platform;

import java.util.UUID;

public interface EventStream {

    boolean offer(String topic, UUID key, Object value);
}
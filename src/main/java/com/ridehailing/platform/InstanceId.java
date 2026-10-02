package com.ridehailing.platform;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/** This process, as a lease holder: host name, process ID and a random suffix, so two processes on one host differ. */
public record InstanceId(String value) {

    public static InstanceId generate() {
        String host = System.getenv().getOrDefault("HOSTNAME", "local");
        String suffix = HexFormat.of().toHexDigits((short) ThreadLocalRandom.current().nextInt());
        return new InstanceId(host + ":" + ProcessHandle.current().pid() + ":" + suffix);
    }
}

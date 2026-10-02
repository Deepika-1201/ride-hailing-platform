package com.ridehailing.platform;

import java.util.Locale;

/** What a process runs (ADR-001, LLD §1.3). Locally one process runs all four. */
public enum Role {
    API,
    REALTIME,
    DISPATCH,
    WORKER;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}

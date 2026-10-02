package com.ridehailing.platform;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.MDC;

/** Keys of the logging context (LLD §1.4), which also feeds event envelopes and audit entries. */
public final class LogContext {

    public static final String REQUEST_ID = "request_id";
    public static final String ROLE = "role";
    public static final String CORRELATION_ID = "correlation_id";
    public static final String CAUSATION_ID = "causation_id";

    private LogContext() {
    }

    /** Runs {@code work} with {@code entries} in the logging context, then restores the previous values. */
    public static void run(Map<String, String> entries, Runnable work) {
        Map<String, String> previous = new HashMap<>();
        entries.forEach((key, value) -> {
            previous.put(key, MDC.get(key));
            MDC.put(key, value);
        });
        try {
            work.run();
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, value);
                }
            });
        }
    }
}

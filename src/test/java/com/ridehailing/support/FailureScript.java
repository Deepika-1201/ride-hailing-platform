package com.ridehailing.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Which keys a scripted handler fails on, and how many more times. */
public final class FailureScript {

    private static final int ALWAYS = -1;

    private final Map<Object, Integer> failuresLeft = new ConcurrentHashMap<>();

    public void failTimes(Object key, int times) {
        failuresLeft.put(key, times);
    }

    public void failAlways(Object key) {
        failuresLeft.put(key, ALWAYS);
    }

    public void heal(Object key) {
        failuresLeft.remove(key);
    }

    public void reset() {
        failuresLeft.clear();
    }

    /** Throws, counting down, if {@code key} is scripted to fail now. */
    void apply(Object key, String handler) {
        boolean[] fail = {false};
        failuresLeft.computeIfPresent(key, (_, left) -> {
            fail[0] = true;
            return left == ALWAYS ? left : left == 1 ? null : left - 1;
        });
        if (fail[0]) {
            throw new IllegalStateException("Scripted failure in " + handler);
        }
    }
}

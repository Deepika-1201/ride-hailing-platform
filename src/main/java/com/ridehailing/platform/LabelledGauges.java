package com.ridehailing.platform;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gauges of one name and one set of label keys, set together from fresh counts (LLD §16.1). A label set appears with
 * its first count, and reads 0 once a refresh no longer has it, so no gauge keeps a stale value.
 */
public final class LabelledGauges {

    private final MeterRegistry meters;
    private final String name;
    private final List<String> keys;
    private final Map<List<String>, AtomicLong> values = new ConcurrentHashMap<>();

    public LabelledGauges(MeterRegistry meters, String name, List<String> keys) {
        this.meters = meters;
        this.name = name;
        this.keys = List.copyOf(keys);
    }

    /** Each count's key holds the label values in the order of the label keys. */
    public synchronized void set(Map<List<String>, Long> counts) {
        values.forEach((labels, value) -> value.set(counts.getOrDefault(labels, 0L)));
        counts.forEach((labels, count) -> values.computeIfAbsent(labels, this::register).set(count));
    }

    private AtomicLong register(List<String> labels) {
        if (labels.size() != keys.size()) {
            throw new IllegalArgumentException(name + " has labels " + keys + ", not " + labels);
        }
        AtomicLong value = new AtomicLong();
        Tags tags = Tags.empty();
        for (int i = 0; i < keys.size(); i++) {
            tags = tags.and(keys.get(i), labels.get(i));
        }
        Gauge.builder(name, value, AtomicLong::get).tags(tags).register(meters);
        return value;
    }
}

package com.ridehailing.support;

import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.EventEnvelope;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Consumes {@value #EVENT_TYPE} events: records an effect, runs the test's hook, then fails if scripted to, so a failure
 * always comes after a write that the rollback must undo.
 */
public class ScriptedConsumer implements EventConsumer {

    public static final String EVENT_TYPE = "TestHappened";

    public final FailureScript script = new FailureScript();

    private final String name;
    private final JdbcClient jdbc;
    private volatile Consumer<EventEnvelope> hook = _ -> { };

    public ScriptedConsumer(String name, JdbcClient jdbc) {
        this.name = name;
        this.jdbc = jdbc;
    }

    public void afterEffect(Consumer<EventEnvelope> hook) {
        this.hook = hook;
    }

    public void reset() {
        script.reset();
        hook = _ -> { };
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(EVENT_TYPE);
    }

    @Override
    public void handle(EventEnvelope event) {
        Effects.record(jdbc, name, event.eventId().toString());
        hook.accept(event);
        script.apply(event.eventId(), name);
    }
}

package com.ridehailing.support;

import com.ridehailing.platform.DueTimer;
import com.ridehailing.platform.TimerHandler;
import com.ridehailing.platform.TimerKind;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Fires {@code TEST_TIMER}: records an effect keyed by the aggregate, runs the hook, then fails if scripted to. */
public class ScriptedTimerHandler implements TimerHandler {

    public enum Kind implements TimerKind {
        TEST_TIMER,
        UNHANDLED_TIMER
    }

    public static final String SOURCE = "timer";

    public final FailureScript script = new FailureScript();

    private final JdbcClient jdbc;
    private volatile Consumer<DueTimer> hook = _ -> { };

    public ScriptedTimerHandler(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void afterEffect(Consumer<DueTimer> hook) {
        this.hook = hook;
    }

    public void reset() {
        script.reset();
        hook = _ -> { };
    }

    @Override
    public TimerKind kind() {
        return Kind.TEST_TIMER;
    }

    @Override
    public void fire(DueTimer timer) {
        Effects.record(jdbc, SOURCE, timer.aggregateId().toString());
        hook.accept(timer);
        script.apply(timer.aggregateId(), "timer handler");
    }
}

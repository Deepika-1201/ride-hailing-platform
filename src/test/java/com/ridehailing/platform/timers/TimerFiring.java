package com.ridehailing.platform.timers;

import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Fires timers as the poller would, for tests whose background loops are stopped. */
@TestComponent
public class TimerFiring {

    private final ObjectProvider<TimerPoller> poller;
    private final JdbcClient jdbc;

    TimerFiring(ObjectProvider<TimerPoller> poller, JdbcClient jdbc) {
        this.poller = poller;
        this.jdbc = jdbc;
    }

    /** Fires the most overdue timer; answers whether one was due. */
    public boolean fireNext() {
        return poller.getObject().fireNext();
    }

    /** Makes the aggregate's timers of the kind due now; answers how many there were. */
    public int makeDue(String kind, UUID aggregateId) {
        return jdbc.sql("""
                        UPDATE platform.timers SET due_at = now() - interval '1 second'
                        WHERE kind = :kind AND aggregate_id = :aggregateId
                        """)
                .param("kind", kind)
                .param("aggregateId", aggregateId)
                .update();
    }

    /** Fires every due timer, the tests' own and any others'. */
    public void fireAllDue() {
        while (fireNext()) {
            // keep firing
        }
    }

    public boolean exists(String kind, UUID aggregateId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM platform.timers WHERE kind = :kind AND aggregate_id = :id)")
                .param("kind", kind)
                .param("id", aggregateId)
                .query(Boolean.class)
                .single();
    }
}

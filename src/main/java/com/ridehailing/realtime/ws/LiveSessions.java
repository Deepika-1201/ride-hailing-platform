package com.ridehailing.realtime.ws;

import com.ridehailing.platform.ConditionalOnRole;
import com.ridehailing.platform.Role;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** The sessions this node holds, counted in {@code websocket_connections{kind}} and their ends in closes (LLD §14.7). */
@Component
@ConditionalOnRole(Role.REALTIME)
class LiveSessions {

    private final Map<String, Session> byId = new ConcurrentHashMap<>();
    private final MeterRegistry meters;

    LiveSessions(MeterRegistry meters) {
        this.meters = meters;
        for (Session.Kind kind : Session.Kind.values()) {
            Gauge.builder("websocket.connections", () -> byId.values().stream()
                            .filter(session -> session.kind() == kind).count())
                    .tag("kind", kind.tag())
                    .register(meters);
        }
    }

    void add(Session session) {
        byId.put(session.id(), session);
    }

    Session get(String id) {
        return byId.get(id);
    }

    /** Releases the session and counts why it ended. */
    void closed(String id) {
        Session session = byId.remove(id);
        if (session != null) {
            session.release();
            meters.counter("websocket.closes", "reason", session.closeReason()).increment();
        }
    }

    Collection<Session> all() {
        return List.copyOf(byId.values());
    }

    boolean isEmpty() {
        return byId.isEmpty();
    }
}

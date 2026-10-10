package com.ridehailing.realtime.ws;

import com.ridehailing.platform.ConditionalOnRole;
import com.ridehailing.platform.Role;
import com.ridehailing.realtime.RealtimeProperties;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

/**
 * Draining on shutdown (LLD §14.5, NFR-12), before the web server stops: readiness goes down and new handshakes get
 * {@code 503}; each session is told to reconnect after a delay spread over {@code drain-spread}; sessions still here
 * after {@code drain-timeout} are closed with 1001.
 */
@Component
@ConditionalOnRole(Role.REALTIME)
class Drain implements SmartLifecycle {

    /** Before the web server's graceful shutdown, at DEFAULT_PHASE − 1024. */
    private static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 512;
    private static final long POLL_MS = 50;
    private static final Logger log = LoggerFactory.getLogger(Drain.class);

    private final LiveSessions sessions;
    private final ApplicationEventPublisher events;
    private final RealtimeProperties properties;
    private volatile boolean running;
    private volatile boolean draining;

    Drain(LiveSessions sessions, ApplicationEventPublisher events, RealtimeProperties properties) {
        this.sessions = sessions;
        this.events = events;
        this.properties = properties;
    }

    boolean draining() {
        return draining;
    }

    @Override
    public void start() {
        draining = false;
        running = true;
    }

    @Override
    public void stop() {
        draining = true;
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
        long spreadMs = properties.drainSpread().toMillis();
        int told = 0;
        for (Session session : sessions.all()) {
            session.send(new Messages.Reconnect(ThreadLocalRandom.current().nextLong(spreadMs + 1)));
            told++;
        }
        long deadline = System.nanoTime() + properties.drainTimeout().toNanos();
        while (!sessions.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int left = 0;
        for (Session session : sessions.all()) {
            session.close(CloseStatus.GOING_AWAY, "drain");
            left++;
        }
        if (told > 0) {
            log.info("Drained {} WebSocket sessions; closed the {} still connected", told, left);
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isPauseable() {
        return false;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}

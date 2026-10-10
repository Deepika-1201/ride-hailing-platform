package com.ridehailing.realtime.ws;

import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.OnlineDriver;
import com.ridehailing.location.LocationProperties;
import com.ridehailing.platform.ConditionalOnRole;
import com.ridehailing.platform.Role;
import com.ridehailing.realtime.RealtimeProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

/**
 * This node's loops over its sessions (LLD §14.6, §14.7): heartbeats every {@code heartbeat-every}, and the operations
 * map every {@code ops-every}. Like the trip point flusher, they aren't background workers, so they run with
 * {@code ride.workers.autostart=false} too.
 */
@Component
@ConditionalOnRole(Role.REALTIME)
class NodeLoops implements SmartLifecycle {

    static final CloseStatus IDLE = CloseStatus.SESSION_NOT_RELIABLE.withReason("Idle");

    private static final Logger log = LoggerFactory.getLogger(NodeLoops.class);

    private final LiveSessions sessions;
    private final LiveIndex index;
    private final Clock clock;
    private final RealtimeProperties properties;
    private final LocationProperties location;
    private ScheduledExecutorService loops;

    NodeLoops(LiveSessions sessions, LiveIndex index, Clock clock, RealtimeProperties properties,
            LocationProperties location) {
        this.sessions = sessions;
        this.index = index;
        this.clock = clock;
        this.properties = properties;
        this.location = location;
    }

    /** Pings a session once {@code ping-every} has passed, and closes one silent for {@code idle-timeout}. */
    void heartbeats() {
        long now = System.nanoTime();
        for (Session session : sessions.all()) {
            if (now - session.heardAt() >= properties.idleTimeout().toNanos()) {
                session.close(IDLE, "idle");
            } else if (now - session.pingedAt() >= properties.pingEvery().toNanos()) {
                session.ping();
            }
        }
    }

    /** Drivers in each operations viewport, stale when silent longer than the freshness window. */
    void snapshots() {
        for (Session session : sessions.all()) {
            Session.Viewport viewport = session.viewport();
            if (viewport == null) {
                continue;
            }
            Instant now = clock.instant();
            Instant fresh = now.minus(location.freshness());
            List<OnlineDriver> drivers = index.snapshot(viewport.cityId(), viewport.box(), properties.opsMaxDrivers());
            session.send(new Messages.OpsSnapshot(viewport.cityId(), now, drivers.size() >= properties.opsMaxDrivers(),
                    drivers.stream().map(driver -> new Messages.OpsDriver(driver.driverId(), driver.position().lat(),
                            driver.position().lon(), driver.status(), driver.category(), driver.rideId(),
                            driver.lastSeen().isBefore(fresh))).toList()));
        }
    }

    @Override
    public synchronized void start() {
        if (loops == null) {
            loops = Executors.newScheduledThreadPool(2, Thread.ofVirtual().name("realtime-loop-", 0).factory());
            schedule(this::heartbeats, properties.heartbeatEvery().toMillis());
            schedule(this::snapshots, properties.opsEvery().toMillis());
        }
    }

    @Override
    public synchronized void stop() {
        if (loops != null) {
            loops.shutdownNow();
            loops = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return loops != null;
    }

    /** A failing round is logged, and the next one runs anyway. */
    private void schedule(Runnable round, long everyMs) {
        loops.scheduleWithFixedDelay(() -> {
            try {
                round.run();
            } catch (RuntimeException e) {
                log.warn("A realtime loop round failed", e);
            }
        }, everyMs, everyMs, TimeUnit.MILLISECONDS);
    }
}

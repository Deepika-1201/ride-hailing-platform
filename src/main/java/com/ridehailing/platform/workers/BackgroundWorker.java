package com.ridehailing.platform.workers;

import com.ridehailing.platform.Role;
import java.time.Duration;
import org.springframework.context.SmartLifecycle;

/**
 * A background loop that starts with the application (unless {@code ride.workers.autostart=false}) and stops before
 * the connection pool closes.
 */
public abstract class BackgroundWorker implements SmartLifecycle {

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    private final String name;
    private final Role role;
    private final int threads;
    private final Duration pollInterval;
    private final boolean autostart;
    private final Object lifecycle = new Object();
    private WorkerLoop loop;

    protected BackgroundWorker(String name, Role role, int threads, Duration pollInterval, WorkerProperties properties) {
        this.name = name;
        this.role = role;
        this.threads = threads;
        this.pollInterval = pollInterval;
        this.autostart = properties.autostart();
    }

    /** One unit of work; returns whether it found something to do, in which case it runs again at once. */
    protected abstract boolean work();

    /** Called after the loop's threads have ended. */
    protected void stopped() {
    }

    @Override
    public void start() {
        synchronized (lifecycle) {
            if (loop == null) {
                loop = new WorkerLoop(name, role, threads, pollInterval, this::work);
                loop.start();
            }
        }
    }

    @Override
    public void stop() {
        synchronized (lifecycle) {
            if (loop != null) {
                loop.stop(STOP_TIMEOUT);
                loop = null;
                stopped();
            }
        }
    }

    @Override
    public boolean isRunning() {
        synchronized (lifecycle) {
            return loop != null;
        }
    }

    @Override
    public boolean isAutoStartup() {
        return autostart;
    }
}

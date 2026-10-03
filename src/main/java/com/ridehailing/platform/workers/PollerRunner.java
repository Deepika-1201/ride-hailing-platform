package com.ridehailing.platform.workers;

import com.ridehailing.platform.Poller;
import com.ridehailing.platform.roles.RoleProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Runs every {@link Poller} of this process's roles on its own threads (LLD §5.7). */
@Component
class PollerRunner implements SmartLifecycle {

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    private final List<Poller> pollers;
    private final boolean autostart;
    private final List<WorkerLoop> loops = new ArrayList<>();

    PollerRunner(ObjectProvider<Poller> pollers, RoleProperties roles, WorkerProperties workers) {
        Set<String> names = new HashSet<>();
        this.pollers = pollers.stream().filter(poller -> roles.roles().contains(poller.role())).toList();
        this.pollers.forEach(poller -> {
            if (!names.add(poller.name())) {
                throw new IllegalStateException("Two pollers are named " + poller.name());
            }
        });
        this.autostart = workers.autostart();
    }

    List<Poller> pollers() {
        return pollers;
    }

    @Override
    public synchronized void start() {
        if (!loops.isEmpty()) {
            return;
        }
        for (Poller poller : pollers) {
            WorkerLoop loop = new WorkerLoop(poller.name(), poller.role(), poller.threads(), poller.interval(),
                    poller::poll);
            loops.add(loop);
            loop.start();
        }
    }

    @Override
    public synchronized void stop() {
        loops.forEach(loop -> loop.stop(STOP_TIMEOUT));
        loops.clear();
    }

    @Override
    public synchronized boolean isRunning() {
        return !loops.isEmpty();
    }

    @Override
    public boolean isAutoStartup() {
        return autostart;
    }
}

package com.ridehailing.platform.jobs;

import com.ridehailing.platform.InstanceId;
import com.ridehailing.platform.Leases;
import com.ridehailing.platform.RecurringJob;
import com.ridehailing.platform.roles.RoleProperties;
import com.ridehailing.platform.workers.WorkerLoop;
import com.ridehailing.platform.workers.WorkerProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs each recurring job of this process's roles once per interval across the cluster (LLD §5.7). The lease
 * {@code job:<name>} is taken with a TTL of one interval and never renewed or released, so its expiry schedules the
 * next run, by the database clock alone.
 */
@Component
class RecurringJobRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RecurringJobRunner.class);
    private static final Duration MIN_CHECK = Duration.ofSeconds(1);
    private static final Duration MAX_CHECK = Duration.ofMinutes(1);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    private final List<RecurringJob> jobs;
    private final Leases leases;
    private final String holder;
    private final boolean autostart;
    private final List<WorkerLoop> loops = new ArrayList<>();

    RecurringJobRunner(ObjectProvider<RecurringJob> jobs, RoleProperties roles, Leases leases, InstanceId instance,
            WorkerProperties workers) {
        Set<String> names = new HashSet<>();
        this.jobs = jobs.stream().filter(job -> roles.roles().contains(job.role())).toList();
        this.jobs.forEach(job -> {
            if (!names.add(job.name())) {
                throw new IllegalStateException("Two recurring jobs are named " + job.name());
            }
        });
        this.leases = leases;
        this.holder = instance.value();
        this.autostart = workers.autostart();
    }

    List<RecurringJob> jobs() {
        return jobs;
    }

    /** Runs the job unless a node ran it within its interval; returns whether this call ran it. */
    boolean runIfDue(RecurringJob job) {
        if (leases.acquire("job:" + job.name(), holder, job.interval()).isEmpty()) {
            return false;
        }
        long started = System.nanoTime();
        try {
            job.run();
            log.info("Job {} finished in {} ms", job.name(), (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException e) {
            log.error("Job {} failed; it runs again after its interval", job.name(), e);
        }
        return true;
    }

    @Override
    public synchronized void start() {
        if (!loops.isEmpty()) {
            return;
        }
        for (RecurringJob job : jobs) {
            WorkerLoop loop = new WorkerLoop("job-" + job.name(), job.role(), 1, checkEvery(job), () -> {
                runIfDue(job);
                return false;
            });
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

    /** A quarter of the interval, between 1 s and 1 min, so a free lease is noticed soon after it expires. */
    private static Duration checkEvery(RecurringJob job) {
        Duration quarter = job.interval().dividedBy(4);
        return quarter.compareTo(MIN_CHECK) < 0 ? MIN_CHECK : quarter.compareTo(MAX_CHECK) > 0 ? MAX_CHECK : quarter;
    }
}

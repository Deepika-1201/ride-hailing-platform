package com.ridehailing.platform.workers;

import com.ridehailing.platform.LogContext;
import com.ridehailing.platform.Role;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Runs a unit of work repeatedly on virtual threads with the role in the logging context (LLD §1.3). A thread that
 * finds nothing to do waits for the poll interval; after failures it waits longer, up to 30 seconds.
 */
public final class WorkerLoop {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoop.class);
    private static final Duration MAX_FAILURE_WAIT = Duration.ofSeconds(30);

    private final String name;
    private final Role role;
    private final int threads;
    private final Duration pollInterval;
    private final BooleanSupplier work;
    private final List<Thread> running = new ArrayList<>();
    private CountDownLatch stopping = new CountDownLatch(0);

    /** {@code work} returns whether it found something to do; if so, it runs again at once. */
    public WorkerLoop(String name, Role role, int threads, Duration pollInterval, BooleanSupplier work) {
        this.name = name;
        this.role = role;
        this.threads = threads;
        this.pollInterval = pollInterval;
        this.work = work;
    }

    public synchronized void start() {
        if (!running.isEmpty()) {
            return;
        }
        CountDownLatch signal = new CountDownLatch(1);
        stopping = signal;
        for (int index = 0; index < threads; index++) {
            running.add(Thread.ofVirtual().name(name + "-" + index).start(() -> run(signal)));
        }
        log.info("Started {} with {} threads", name, threads);
    }

    /** Lets each thread finish its unit of work, then interrupts any still running at the timeout. */
    public synchronized void stop(Duration timeout) {
        stopping.countDown();
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Thread thread : running) {
            try {
                if (!thread.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())))) {
                    thread.interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                thread.interrupt();
            }
        }
        running.clear();
    }

    private void run(CountDownLatch signal) {
        MDC.put(LogContext.ROLE, role.id());
        int failures = 0;
        while (signal.getCount() > 0) {
            boolean worked = false;
            try {
                worked = work.getAsBoolean();
                failures = 0;
            } catch (RuntimeException e) {
                failures++;
                log.error("{} failed ({} in a row)", name, failures, e);
            }
            if (!worked) {
                Duration wait = failures == 0 ? pollInterval : backoff(failures);
                try {
                    signal.await(wait.toNanos(), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Doubles from the poll interval up to 30 s, with jitter so failing threads spread out. */
    private Duration backoff(int failures) {
        double exponential = pollInterval.toMillis() * Math.pow(2, Math.min(failures, 20));
        long capped = (long) Math.min(exponential, MAX_FAILURE_WAIT.toMillis());
        return Duration.ofMillis(capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1));
    }
}

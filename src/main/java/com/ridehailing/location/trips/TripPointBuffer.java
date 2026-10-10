package com.ridehailing.location.trips;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Trip points on their way to the database (LLD §9.8). The ingestion adds them; this process's loop writes them every
 * 2 s or at 500 points, and once more on shutdown. A failed write keeps its points, and points older than 30 s, or
 * beyond 50,000, are dropped oldest first and counted. The loop isn't a background worker, so it runs with
 * {@code ride.workers.autostart=false} too.
 */
@Component
public class TripPointBuffer implements SmartLifecycle {

    static final Duration FLUSH_EVERY = Duration.ofSeconds(2);
    static final int FLUSH_AT = 500;
    static final Duration KEEP_FOR = Duration.ofSeconds(30);
    static final int MAX_BUFFERED = 50_000;

    private static final Logger log = LoggerFactory.getLogger(TripPointBuffer.class);
    /** Stops after the web server (at DEFAULT_PHASE − 2048), so no request adds points after the last write. */
    private static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    private final TripPointRepository repository;
    private final Clock clock;
    private final Counter dropped;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition full = lock.newCondition();
    private final ReentrantLock writing = new ReentrantLock();
    private final ArrayDeque<TripPoint> points = new ArrayDeque<>();
    private final Object lifecycle = new Object();
    private volatile boolean running;
    private Thread loop;

    TripPointBuffer(TripPointRepository repository, Clock clock, MeterRegistry meters) {
        this.repository = repository;
        this.clock = clock;
        this.dropped = meters.counter("trip.points.dropped");
    }

    public void add(TripPoint point) {
        boolean overflowed;
        lock.lock();
        try {
            points.addLast(point);
            overflowed = points.size() > MAX_BUFFERED;
            if (overflowed) {
                points.removeFirst();
            }
            if (points.size() >= FLUSH_AT) {
                full.signalAll();
            }
        } finally {
            lock.unlock();
        }
        if (overflowed) {
            dropped.increment();
        }
    }

    /**
     * Writes the points buffered now, 500 to a statement, after any write under way; answers how many it wrote. So when
     * it returns, every point added before the call is written or kept. Tests call it too.
     */
    public int flush() {
        writing.lock();
        try {
            return write();
        } finally {
            writing.unlock();
        }
    }

    private int write() {
        List<TripPoint> taken;
        lock.lock();
        try {
            taken = List.copyOf(points);
            points.clear();
        } finally {
            lock.unlock();
        }
        int written = 0;
        try {
            while (written < taken.size()) {
                List<TripPoint> batch = taken.subList(written, Math.min(written + FLUSH_AT, taken.size()));
                repository.insert(batch);
                written += batch.size();
            }
        } catch (RuntimeException e) {
            int lost = keep(taken.subList(written, taken.size()));
            log.warn("Writing {} trip points failed; kept for another try, {} dropped: {}", taken.size() - written,
                    lost, e.toString());
        }
        return written;
    }

    int buffered() {
        lock.lock();
        try {
            return points.size();
        } finally {
            lock.unlock();
        }
    }

    /** Puts the points back in front, then drops those past their age or the limit; answers how many it dropped. */
    private int keep(List<TripPoint> unwritten) {
        Instant oldestKept = clock.instant().minus(KEEP_FOR);
        int lost = 0;
        lock.lock();
        try {
            for (ListIterator<TripPoint> back = unwritten.listIterator(unwritten.size()); back.hasPrevious(); ) {
                points.addFirst(back.previous());
            }
            while (!points.isEmpty()
                    && (points.size() > MAX_BUFFERED || points.peekFirst().receivedAt().isBefore(oldestKept))) {
                points.removeFirst();
                lost++;
            }
        } finally {
            lock.unlock();
        }
        dropped.increment(lost);
        return lost;
    }

    private void run() {
        while (running) {
            lock.lock();
            try {
                if (points.size() < FLUSH_AT) {
                    full.await(FLUSH_EVERY.toMillis(), TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }
            // Stopping writes the rest itself, after this thread has ended.
            if (running) {
                flush();
            }
        }
    }

    @Override
    public void start() {
        synchronized (lifecycle) {
            if (loop == null) {
                running = true;
                loop = Thread.ofVirtual().name("trip-points").start(this::run);
            }
        }
    }

    @Override
    public void stop() {
        synchronized (lifecycle) {
            if (loop == null) {
                return;
            }
            running = false;
            lock.lock();
            try {
                full.signalAll();
            } finally {
                lock.unlock();
            }
            try {
                if (!loop.join(STOP_TIMEOUT)) {
                    log.warn("The trip point loop didn't stop within {}", STOP_TIMEOUT);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            loop = null;
        }
        flush();
    }

    @Override
    public boolean isRunning() {
        synchronized (lifecycle) {
            return loop != null;
        }
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}

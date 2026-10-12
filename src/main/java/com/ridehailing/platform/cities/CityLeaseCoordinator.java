package com.ridehailing.platform.cities;

import com.ridehailing.platform.CityOwnership;
import com.ridehailing.platform.InstanceId;
import com.ridehailing.platform.Leases;
import com.ridehailing.platform.LogContext;
import com.ridehailing.platform.Role;
import com.ridehailing.platform.RoleComponent;
import com.ridehailing.platform.workers.WorkerLoop;
import com.ridehailing.platform.workers.WorkerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;

@RoleComponent(Role.DISPATCH)
@ConditionalOnProperty(prefix = "ride.dispatch.city-ownership", name = "enabled", havingValue = "true")
class CityLeaseCoordinator implements CityOwnership, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CityLeaseCoordinator.class);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    private final Leases leases;
    private final CityOwnershipProperties properties;
    private final String holder;
    private final boolean autostart;
    private final Object state = new Object();
    private final Object maintenance = new Object();
    private final Map<String, Claim> claims = new HashMap<>();
    private final Counter acquired;
    private final Counter renewed;
    private final Counter lost;
    private final Counter failures;
    private boolean accepting = true;
    private long generation;
    private WorkerLoop loop;

    CityLeaseCoordinator(Leases leases, CityOwnershipProperties properties, InstanceId instance, WorkerProperties workers,
            MeterRegistry metrics) {
        this.leases = leases;
        this.properties = properties;
        holder = instance.value();
        autostart = workers.autostart();
        acquired = metrics.counter("dispatch.city.ownership", "outcome", "acquired");
        renewed = metrics.counter("dispatch.city.ownership", "outcome", "renewed");
        lost = metrics.counter("dispatch.city.ownership", "outcome", "lost");
        failures = metrics.counter("dispatch.city.ownership", "outcome", "error");
        Gauge.builder("dispatch.city.ownership.held", this, coordinator -> coordinator.owned().size()).register(metrics);
    }

    @Override
    public Set<String> configuredCities() {
        return properties.cities();
    }

    @Override
    public List<Claim> owned() {
        synchronized (state) {
            return claims.values().stream().sorted(Comparator.comparing(Claim::cityId)).toList();
        }
    }

    void maintain() {
        synchronized (maintenance) {
            long observedGeneration;
            synchronized (state) {
                if (!accepting) {
                    return;
                }
                observedGeneration = generation;
            }
            for (String city : properties.cities().stream().sorted().toList()) {
                Claim previous;
                synchronized (state) {
                    if (!accepting || generation != observedGeneration) {
                        return;
                    }
                    previous = claims.get(city);
                }
                Claim next = null;
                try {
                    if (previous == null) {
                        OptionalLong token = leases.acquire(leaseName(city), holder, properties.ttl());
                        if (token.isPresent()) {
                            next = new Claim(city, holder, token.getAsLong());
                        }
                    } else if (leases.renew(leaseName(city), holder, previous.token(), properties.ttl())) {
                        next = previous;
                    }
                } catch (RuntimeException failure) {
                    failures.increment();
                    log.warn("Could not maintain ownership of city {}", city, failure);
                }
                boolean discard;
                synchronized (state) {
                    discard = !accepting || generation != observedGeneration;
                    if (!discard) {
                        if (next == null) {
                            forget(previous);
                        } else {
                            claims.put(city, next);
                            (previous == null ? acquired : renewed).increment();
                        }
                    }
                }
                if (discard && next != null) {
                    release(next);
                }
            }
        }
    }

    @Override
    public boolean runIfOwned(Claim claim, Runnable databaseWork) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(databaseWork, "databaseWork");
        if (!current(claim)) {
            return false;
        }
        try {
            boolean committed = leases.runIfHeld(leaseName(claim.cityId()), claim.holder(), claim.token(), () -> {
                if (!current(claim)) {
                    throw new OwnershipChanged();
                }
                LogContext.run(Map.of(LogContext.ROLE, Role.DISPATCH.id()), databaseWork);
                if (!current(claim)) {
                    throw new OwnershipChanged();
                }
            });
            if (!committed) {
                forget(claim);
            }
            return committed;
        } catch (OwnershipChanged changed) {
            forget(claim);
            return false;
        } catch (DataAccessException failure) {
            forget(claim);
            failures.increment();
            throw failure;
        }
    }

    private boolean current(Claim claim) {
        synchronized (state) {
            return accepting && claim.equals(claims.get(claim.cityId()));
        }
    }

    private void forget(Claim claim) {
        synchronized (state) {
            if (claim != null && claims.remove(claim.cityId(), claim)) {
                lost.increment();
            }
        }
    }

    private void release(Claim claim) {
        try {
            leases.release(leaseName(claim.cityId()), claim.holder(), claim.token());
        } catch (RuntimeException failure) {
            failures.increment();
            log.warn("Could not release ownership of city {}; the lease will expire", claim.cityId(), failure);
        }
    }

    private static String leaseName(String cityId) {
        return "city-owner:" + cityId;
    }

    @Override
    public synchronized void start() {
        if (loop != null) {
            return;
        }
        synchronized (state) {
            accepting = true;
        }
        loop = new WorkerLoop("city-ownership", Role.DISPATCH, 1, properties.renewEvery(), () -> {
            maintain();
            return false;
        });
        loop.start();
    }

    @Override
    public synchronized void stop() {
        long deadline = System.nanoTime() + STOP_TIMEOUT.toNanos();
        List<Claim> released;
        synchronized (state) {
            accepting = false;
            generation++;
            released = List.copyOf(claims.values());
            claims.clear();
        }
        if (loop != null) {
            loop.stop(STOP_TIMEOUT);
            loop = null;
        }
        if (!released.isEmpty() && System.nanoTime() < deadline) {
            Thread cleanup = Thread.ofVirtual().name("city-ownership-release").start(() -> {
                for (Claim claim : released) {
                    if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
                        return;
                    }
                    release(claim);
                }
            });
            try {
                if (!cleanup.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())))) {
                    cleanup.interrupt();
                }
            } catch (InterruptedException interrupted) {
                cleanup.interrupt();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return loop != null;
    }

    @Override
    public boolean isAutoStartup() {
        return autostart;
    }

    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 200;
    }

    private static final class OwnershipChanged extends RuntimeException {
    }
}
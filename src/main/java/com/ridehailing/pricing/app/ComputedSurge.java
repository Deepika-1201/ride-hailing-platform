package com.ridehailing.pricing.app;

import com.ridehailing.geography.GeographyApi;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.platform.CityOwnership;
import com.ridehailing.platform.CityOwnership.Claim;
import com.ridehailing.platform.Poller;
import com.ridehailing.platform.Role;
import com.ridehailing.platform.RoleComponent;
import com.ridehailing.pricing.db.ComputedSurgeRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RoleComponent(Role.DISPATCH)
@ConditionalOnProperty(prefix = "ride.pricing.computed-surge", name = "enabled", havingValue = "true")
class ComputedSurge implements Poller {

    private static final Logger log = LoggerFactory.getLogger(ComputedSurge.class);
    private static final Duration RECOVERY_GRACE = Duration.ofMinutes(1);
    private final CityOwnership ownership;
    private final ComputedSurgeRepository repository;
    private final ComputedSurgeProperties properties;
    private final GeographyApi geography;
    private final LiveIndex index;
    private final Clock clock;
    private final Map<String, Counter> outcomes = new HashMap<>();

    ComputedSurge(CityOwnership ownership, ComputedSurgeRepository repository, ComputedSurgeProperties properties,
            GeographyApi geography, LiveIndex index, Clock clock, MeterRegistry metrics) {
        if (!ownership.configuredCities().containsAll(properties.cities().keySet())) {
            throw new IllegalArgumentException("Computed surge cities must be included in this dispatch process's city ownership");
        }
        this.ownership = ownership;
        this.repository = repository;
        this.properties = properties;
        this.geography = geography;
        this.index = index;
        this.clock = clock;
        for (String outcome : List.of("published", "inactive", "recovering", "not_owner", "stale_or_not_due", "error")) {
            outcomes.put(outcome, metrics.counter("pricing.surge.cycles", "outcome", outcome));
        }
    }

    @Override
    public String name() {
        return "computed-surge";
    }

    @Override
    public Role role() {
        return Role.DISPATCH;
    }

    @Override
    public int threads() {
        return 1;
    }

    @Override
    public Duration interval() {
        return Duration.ofSeconds(5);
    }

    @Override
    public boolean poll() {
        boolean published = false;
        for (Claim claim : ownership.owned()) {
            if (!properties.cities().containsKey(claim.cityId())) {
                continue;
            }
            try {
                if (repository.due(claim.cityId())) {
                    published |= update(claim);
                }
            } catch (RuntimeException failure) {
                outcomes.get("error").increment();
                log.warn("Computed surge skipped city {} after a failed measurement or publication", claim.cityId(), failure);
            }
        }
        return published;
    }

    private boolean update(Claim claim) {
        if (geography.city(claim.cityId()).filter(GeographyApi.CityView::active).isEmpty()) {
            outcomes.get("inactive").increment();
            return false;
        }
        Instant epoch = index.epoch(claim.cityId());
        if (epoch.isAfter(clock.instant().minus(RECOVERY_GRACE))) {
            outcomes.get("recovering").increment();
            return false;
        }
        var observation = repository.observe(claim.cityId());
        var drivers = index.available(claim.cityId(), properties.maxDrivers());
        var supply = geography.countByZone(claim.cityId(), drivers.stream().map(LiveIndex.OnlineDriver::position).toList());
        if (!epoch.equals(index.epoch(claim.cityId()))) {
            outcomes.get("recovering").increment();
            return false;
        }
        AtomicBoolean updated = new AtomicBoolean();
        boolean held = ownership.runIfOwned(claim, () -> updated.set(repository.commit(claim, observation, supply,
                properties.cities().get(claim.cityId()))));
        outcomes.get(!held ? "not_owner" : updated.get() ? "published" : "stale_or_not_due").increment();
        return held && updated.get();
    }
}
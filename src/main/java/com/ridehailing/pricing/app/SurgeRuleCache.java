package com.ridehailing.pricing.app;

import com.ridehailing.pricing.db.SurgeRuleRepository;
import com.ridehailing.pricing.db.SurgeRuleRepository.SurgeRule;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Each city's active surge rules, reloaded after 60 s and at once after a change commits in this process (HLD §5.4,
 * LLD §10.3).
 */
@Component
public class SurgeRuleCache {

    static final Duration TTL = Duration.ofSeconds(60);

    private final Function<String, List<SurgeRule>> loader;
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Long> generations = new ConcurrentHashMap<>();

    @Autowired
    SurgeRuleCache(SurgeRuleRepository rules, Clock clock) {
        this(rules::activeIn, clock);
    }

    SurgeRuleCache(Function<String, List<SurgeRule>> loader, Clock clock) {
        this.loader = loader;
        this.clock = clock;
    }

    public List<SurgeRule> of(String cityId) {
        Instant now = clock.instant();
        Entry entry = entries.get(cityId);
        if (entry != null && now.isBefore(entry.loadedAt().plus(TTL))) {
            return entry.rules();
        }
        long generation = generations.getOrDefault(cityId, 0L);
        List<SurgeRule> loaded = loader.apply(cityId);
        // A load that started before an invalidation may hold old rules: use them once, but don't keep them.
        entries.compute(cityId, (key, current) ->
                generations.getOrDefault(cityId, 0L) == generation ? new Entry(now, loaded) : current);
        return loaded;
    }

    /** Called after a change to the city's surge rules commits. */
    public void invalidate(String cityId) {
        generations.merge(cityId, 1L, Long::sum);
        entries.remove(cityId);
    }

    private record Entry(Instant loadedAt, List<SurgeRule> rules) {
    }
}

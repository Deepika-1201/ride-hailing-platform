package com.ridehailing.pricing.app;

import com.ridehailing.pricing.app.SurgeWindows.Surge;
import com.ridehailing.pricing.db.ComputedSurgeRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class SurgeSelection {

    private final SurgeRuleCache rules;
    private final ComputedSurgeRepository computed;
    private final ComputedSurgeProperties properties;
    private final Map<Surge.Source, Counter> selected = new EnumMap<>(Surge.Source.class);

    SurgeSelection(SurgeRuleCache rules, ComputedSurgeRepository computed, ComputedSurgeProperties properties,
            MeterRegistry metrics) {
        this.rules = rules;
        this.computed = computed;
        this.properties = properties;
        for (Surge.Source source : Surge.Source.values()) {
            selected.put(source, metrics.counter("pricing.surge.selection", "source", source.name().toLowerCase(Locale.ROOT)));
        }
    }

    Surge at(String cityId, String zoneId, ZonedDateTime local) {
        if (properties.enabled() && properties.cities().containsKey(cityId)) {
            Optional<BigDecimal> multiplier = computed.current(cityId, zoneId);
            if (multiplier.isPresent()) {
                selected.get(Surge.Source.COMPUTED).increment();
                return new Surge(multiplier.orElseThrow(), Surge.Source.COMPUTED);
            }
        }
        Surge surge = SurgeWindows.at(rules.of(cityId), zoneId, local);
        selected.get(surge.source()).increment();
        return surge;
    }
}
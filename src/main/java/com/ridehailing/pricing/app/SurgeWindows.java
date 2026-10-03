package com.ridehailing.pricing.app;

import com.ridehailing.pricing.db.SurgeRuleRepository.SurgeRule;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Which surge rules apply to a zone at a local time (LLD §10.3). A rule's days are the days its window starts, so a
 * window that wraps past midnight belongs to the day before for its early hours.
 */
public final class SurgeWindows {

    static final BigDecimal NONE = new BigDecimal("1.00");
    static final BigDecimal CAP = new BigDecimal("2.00");

    private SurgeWindows() {
    }

    /** The highest multiplier among the zone's active rules covering the time, capped at 2.00; 1.00 if none. */
    public static Surge at(List<SurgeRule> rules, String zoneId, ZonedDateTime local) {
        return rules.stream()
                .filter(rule -> rule.active() && rule.zoneId().equals(zoneId) && covers(rule, local))
                .map(SurgeRule::multiplier)
                .max(BigDecimal::compareTo)
                .map(highest -> new Surge(highest.min(CAP), Surge.Source.RULE))
                .orElse(new Surge(NONE, Surge.Source.NONE));
    }

    static boolean covers(SurgeRule rule, ZonedDateTime local) {
        LocalTime time = local.toLocalTime();
        int today = local.getDayOfWeek().getValue();
        boolean afterStart = !time.isBefore(rule.startLocal());
        boolean beforeEnd = time.isBefore(rule.endLocal());
        if (rule.startLocal().isBefore(rule.endLocal())) {
            return afterStart && beforeEnd && rule.daysOfWeek().contains(today);
        }
        int yesterday = local.minusDays(1).getDayOfWeek().getValue();
        return (afterStart && rule.daysOfWeek().contains(today))
                || (beforeEnd && rule.daysOfWeek().contains(yesterday));
    }

    public record Surge(BigDecimal multiplier, Source source) {

        public enum Source {
            NONE,
            RULE
        }
    }
}

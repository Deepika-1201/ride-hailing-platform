package com.ridehailing.pricing.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.pricing.app.SurgeWindows.Surge;
import com.ridehailing.pricing.db.SurgeRuleRepository.SurgeRule;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** LLD §10.3: windows by local time, days owned by the day a window starts, the highest multiplier, a 2.00 cap. */
class SurgeWindowsTests {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final String ZONE = "8761892e9ffffff";
    // 2026-10-02 is a Friday.
    private static final SurgeRule WEEKDAY_EVENINGS = rule(ZONE, List.of(1, 2, 3, 4, 5), "17:30", "20:30", "1.40");
    private static final SurgeRule FRIDAY_AND_SATURDAY_NIGHTS = rule(ZONE, List.of(5, 6), "22:00", "02:00", "1.50");

    @Test
    void aWindowIncludesItsStartAndExcludesItsEnd() {
        assertThat(SurgeWindows.covers(WEEKDAY_EVENINGS, at("2026-10-02T17:29:59"))).isFalse();
        assertThat(SurgeWindows.covers(WEEKDAY_EVENINGS, at("2026-10-02T17:30"))).isTrue();
        assertThat(SurgeWindows.covers(WEEKDAY_EVENINGS, at("2026-10-02T20:29:59"))).isTrue();
        assertThat(SurgeWindows.covers(WEEKDAY_EVENINGS, at("2026-10-02T20:30"))).isFalse();
        assertThat(SurgeWindows.covers(WEEKDAY_EVENINGS, at("2026-10-03T18:00"))).as("Saturday").isFalse();
    }

    @Test
    void aWindowAcrossMidnightBelongsToTheDayItStarts() {
        assertThat(SurgeWindows.covers(FRIDAY_AND_SATURDAY_NIGHTS, at("2026-10-02T21:59"))).isFalse();
        assertThat(SurgeWindows.covers(FRIDAY_AND_SATURDAY_NIGHTS, at("2026-10-02T22:00"))).as("Friday night").isTrue();
        assertThat(SurgeWindows.covers(FRIDAY_AND_SATURDAY_NIGHTS, at("2026-10-03T01:59"))).as("Friday's window")
                .isTrue();
        assertThat(SurgeWindows.covers(FRIDAY_AND_SATURDAY_NIGHTS, at("2026-10-03T02:00"))).isFalse();
        assertThat(SurgeWindows.covers(FRIDAY_AND_SATURDAY_NIGHTS, at("2026-10-03T23:00"))).as("Saturday night")
                .isTrue();
        assertThat(SurgeWindows.covers(FRIDAY_AND_SATURDAY_NIGHTS, at("2026-10-04T01:00"))).as("Saturday's window")
                .isTrue();
        assertThat(SurgeWindows.covers(FRIDAY_AND_SATURDAY_NIGHTS, at("2026-10-02T01:00")))
                .as("early Friday is Thursday's window").isFalse();
        assertThat(SurgeWindows.covers(FRIDAY_AND_SATURDAY_NIGHTS, at("2026-10-04T23:00"))).as("Sunday night")
                .isFalse();
    }

    @Test
    void theHighestMatchingMultiplierAppliesInTheZone() {
        SurgeRule evenings = rule(ZONE, List.of(1, 2, 3, 4, 5), "17:00", "21:00", "1.20");
        SurgeRule elsewhere = rule("87618925cffffff", List.of(5), "17:00", "21:00", "1.90");

        assertThat(SurgeWindows.at(List.of(evenings, WEEKDAY_EVENINGS, elsewhere), ZONE, at("2026-10-02T18:00")))
                .isEqualTo(new Surge(new BigDecimal("1.40"), Surge.Source.RULE));
        assertThat(SurgeWindows.at(List.of(evenings, WEEKDAY_EVENINGS), ZONE, at("2026-10-02T17:15")))
                .isEqualTo(new Surge(new BigDecimal("1.20"), Surge.Source.RULE));
    }

    @Test
    void noMatchingRuleMeansNoSurge() {
        assertThat(SurgeWindows.at(List.of(WEEKDAY_EVENINGS), ZONE, at("2026-10-02T12:00")))
                .isEqualTo(new Surge(new BigDecimal("1.00"), Surge.Source.NONE));
    }

    @Test
    void anInactiveRuleDoesntCountInsideItsWindow() {
        SurgeRule inactive = new SurgeRule(UUID.randomUUID(), "blr", ZONE, List.of(5), LocalTime.of(17, 0),
                LocalTime.of(21, 0), new BigDecimal("1.80"), false, 1);

        assertThat(SurgeWindows.at(List.of(inactive, WEEKDAY_EVENINGS), ZONE, at("2026-10-02T18:00")))
                .isEqualTo(new Surge(new BigDecimal("1.40"), Surge.Source.RULE));
    }

    @Test
    void multipliersAreCappedAtTwo() {
        SurgeRule tooHigh = rule(ZONE, List.of(5), "17:00", "21:00", "2.50");

        assertThat(SurgeWindows.at(List.of(tooHigh), ZONE, at("2026-10-02T18:00")).multiplier())
                .isEqualByComparingTo("2.00");
    }

    private static ZonedDateTime at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(KOLKATA);
    }

    private static SurgeRule rule(String zone, List<Integer> days, String start, String end, String multiplier) {
        return new SurgeRule(UUID.randomUUID(), "blr", zone, days, LocalTime.parse(start), LocalTime.parse(end),
                new BigDecimal(multiplier), true, 0);
    }
}

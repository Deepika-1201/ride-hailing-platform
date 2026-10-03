package com.ridehailing.support;

import com.ridehailing.platform.Caller;
import com.ridehailing.pricing.app.RulePublishing;
import com.ridehailing.pricing.app.SurgeRuleAdministration;
import com.ridehailing.pricing.db.RuleRepository.FareRule;
import com.ridehailing.pricing.db.RuleRepository.FeeRule;
import com.ridehailing.pricing.db.SurgeRuleRepository.SurgeRule;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.boot.test.context.TestComponent;

/** Fare, fee and surge rules for test cities, published through pricing's services. */
@TestComponent
public class TestPrices {

    private static final Caller ADMIN = new Caller(Ids.newId(), Set.of(UserRole.ADMIN));

    private final RulePublishing publishing;
    private final SurgeRuleAdministration surgeRules;

    TestPrices(RulePublishing publishing, SurgeRuleAdministration surgeRules) {
        this.publishing = publishing;
        this.surgeRules = surgeRules;
    }

    /** The illustrative MINI rule of LLD §10.2 and a fee rule, both in effect now. */
    public FareRule price(String cityId, String category) {
        FareRule fare = fare(cityId, category, 4_000);
        feeFrom(cityId, category, null);
        return fare;
    }

    /** A fee rule taking effect at {@code effectiveFrom}; null means now. */
    public FeeRule feeFrom(String cityId, String category, Instant effectiveFrom) {
        return publishing.publish(ADMIN, new FeeRule(null, cityId, category, 0, effectiveFrom, 5_000, 7_500, 120, 300,
                300, 2_000, "INR", null));
    }

    /** A fare rule in effect now, like the illustrative one but with this base fare. */
    public FareRule fare(String cityId, String category, long basePaise) {
        return fareFrom(cityId, category, basePaise, null);
    }

    /** Like {@link #fare}, taking effect at {@code effectiveFrom}; null means now. */
    public FareRule fareFrom(String cityId, String category, long basePaise, Instant effectiveFrom) {
        return publishing.publish(ADMIN, new FareRule(null, cityId, category, 0, effectiveFrom, basePaise, 1_400, 150,
                8_000, 1_000, 500, 2_000, "INR", null));
    }

    /** A rule for every day, covering the hour before and after now in Kolkata time. */
    public SurgeRule surgeNow(String cityId, String zoneId, String multiplier) {
        LocalTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).toLocalTime().withSecond(0).withNano(0);
        return surgeRules.create(ADMIN, new SurgeRule(null, cityId, zoneId, List.of(1, 2, 3, 4, 5, 6, 7),
                now.minusHours(1), now.plusHours(1), new BigDecimal(multiplier), true, 0));
    }
}

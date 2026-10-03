package com.ridehailing.pricing.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.pricing.db.Fare;
import com.ridehailing.pricing.db.RuleRepository.FareRule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** LLD §10.2: the worked example, and properties over arbitrary rules, routes and multipliers. */
class FareCalculatorTests {

    /** The illustrative MINI rule: base ₹40, ₹14/km, ₹1.50/min, minimum ₹80, booking fee ₹10, tax 5%, commission 20%. */
    static final FareRule MINI = rule(4_000, 1_400, 150, 8_000, 1_000, 500, 2_000);

    @Test
    void theWorkedExample() {
        Fare fare = FareCalculator.calculate(MINI, 8_400, 26 * 60, new BigDecimal("1.20"));

        assertThat(fare).isEqualTo(new Fare(4_000, 11_760, 3_900, 3_932, 0, 1_000, 1_230, 78, 25_900, 4_934));
    }

    @Test
    void aShortTripPaysTheMinimumPlusTheBookingFeeAndTax() {
        Fare fare = FareCalculator.calculate(MINI, 500, 120, new BigDecimal("1.00"));

        assertThat(fare.minimumTopup()).isEqualTo(8_000 - (4_000 + 700 + 300));
        assertThat(fare.tax()).isEqualTo(450);
        assertThat(fare.total()).isEqualTo(9_500);
    }

    @Test
    void surgeCountsTowardTheMinimum() {
        Fare fare = FareCalculator.calculate(MINI, 500, 120, new BigDecimal("1.40"));

        assertThat(fare.surge()).isEqualTo(2_000);
        assertThat(fare.minimumTopup()).isEqualTo(8_000 - (5_000 + 2_000));
    }

    @Test
    void eachProductIsRoundedHalfUpToThePaisa() {
        // 11,765.6 distance, 3,902.5 time and 9,834.5 surge round up; 1,525.2 tax rounds down.
        Fare fare = FareCalculator.calculate(MINI, 8_404, 1_561, new BigDecimal("1.50"));

        assertThat(fare).isEqualTo(new Fare(4_000, 11_766, 3_903, 9_835, 0, 1_000, 1_525, 71, 32_100, 6_115));
    }

    @Property
    void theComponentsAddUpToTheTotal(@ForAll("rules") FareRule rule, @ForAll @IntRange(max = 300_000) int distanceM,
            @ForAll @IntRange(max = 36_000) int durationS, @ForAll("multipliers") BigDecimal multiplier) {
        Fare fare = FareCalculator.calculate(rule, distanceM, durationS, multiplier);

        assertThat(fare.base() + fare.distance() + fare.time() + fare.surge() + fare.minimumTopup()
                + fare.bookingFee() + fare.tax() + fare.rounding()).isEqualTo(fare.total());
    }

    @Property
    void theTotalIsWholeRupeesAfterRoundingByLessThanOne(@ForAll("rules") FareRule rule,
            @ForAll @IntRange(max = 300_000) int distanceM, @ForAll @IntRange(max = 36_000) int durationS,
            @ForAll("multipliers") BigDecimal multiplier) {
        Fare fare = FareCalculator.calculate(rule, distanceM, durationS, multiplier);

        assertThat(fare.rounding()).isBetween(0L, 99L);
        assertThat(fare.total() % 100).isZero();
    }

    @Property
    void theFareBeforeTheBookingFeeNeverDropsBelowTheMinimum(@ForAll("rules") FareRule rule,
            @ForAll @IntRange(max = 300_000) int distanceM, @ForAll @IntRange(max = 36_000) int durationS,
            @ForAll("multipliers") BigDecimal multiplier) {
        Fare fare = FareCalculator.calculate(rule, distanceM, durationS, multiplier);

        assertThat(fare.base() + fare.distance() + fare.time() + fare.surge() + fare.minimumTopup())
                .isGreaterThanOrEqualTo(rule.minimumPaise());
        assertThat(fare.total()).isGreaterThanOrEqualTo(rule.minimumPaise() + rule.bookingFeePaise());
    }

    @Property
    void theCommissionIsAShareOfTheFareBeforeTax(@ForAll("rules") FareRule rule,
            @ForAll @IntRange(max = 300_000) int distanceM, @ForAll @IntRange(max = 36_000) int durationS,
            @ForAll("multipliers") BigDecimal multiplier) {
        Fare fare = FareCalculator.calculate(rule, distanceM, durationS, multiplier);

        assertThat(fare.commission()).isBetween(0L, fare.total() - fare.tax());
    }

    @Property
    void surgeNeverLowersTheFare(@ForAll("rules") FareRule rule, @ForAll @IntRange(max = 300_000) int distanceM,
            @ForAll @IntRange(max = 36_000) int durationS, @ForAll("multipliers") BigDecimal multiplier) {
        assertThat(FareCalculator.calculate(rule, distanceM, durationS, multiplier).total())
                .isGreaterThanOrEqualTo(FareCalculator.calculate(rule, distanceM, durationS, BigDecimal.ONE).total());
    }

    @Provide
    Arbitrary<FareRule> rules() {
        Arbitrary<Long> paise = Arbitraries.longs().between(0, 100_000);
        Arbitrary<Integer> basisPoints = Arbitraries.integers().between(0, 5_000);
        return Combinators.combine(paise, Arbitraries.longs().between(0, 5_000), Arbitraries.longs().between(0, 1_000),
                        Arbitraries.longs().between(0, 500_000), paise, basisPoints, basisPoints)
                .as((base, perKm, perMin, minimum, bookingFee, taxBp, commissionBp) ->
                        rule(base, perKm, perMin, minimum, bookingFee, taxBp, commissionBp));
    }

    @Provide
    Arbitrary<BigDecimal> multipliers() {
        return Arbitraries.integers().between(100, 200).map(hundredths -> BigDecimal.valueOf(hundredths, 2));
    }

    static FareRule rule(long base, long perKm, long perMin, long minimum, long bookingFee, int taxBp,
            int commissionBp) {
        return new FareRule(UUID.randomUUID(), "blr", "MINI", 1, Instant.EPOCH, base, perKm, perMin, minimum,
                bookingFee, taxBp, commissionBp, "INR", Instant.EPOCH);
    }
}

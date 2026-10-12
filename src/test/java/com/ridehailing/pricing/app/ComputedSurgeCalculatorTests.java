package com.ridehailing.pricing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.pricing.app.ComputedSurgeCalculator.Step;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComputedSurgeCalculatorTests {

    private final ComputedSurgeCalculator calculator = new ComputedSurgeCalculator(List.of(
            step("0", "1"), step("1", "1.40"), step("2", "1.80"), step("3", "2")));

    @Test
    void weightsQuotesAndNeighboringSupplyBeforeSelectingAnInclusiveStep() {
        var result = calculator.calculate(1, 5, 1, 2, decimal("1.30"));
        assertThat(result.demand()).isEqualByComparingTo("2.00");
        assertThat(result.supply()).isEqualByComparingTo("2.00");
        assertThat(result.target()).isEqualByComparingTo("1.40");
        assertThat(result.multiplier()).isEqualByComparingTo("1.40");
        assertThat(calculator.calculate(1, 4, 1, 2, decimal("1.30")).target()).isEqualByComparingTo("1.00");
    }

    @Test
    void roundingARatioCannotPrematurelySelectAHigherStep() {
        assertThat(calculator.calculate(9_999_999, 4, 10_000_000, 0, decimal("1")).target()).isEqualByComparingTo("1.00");
        assertThat(calculator.calculate(10_000_000, 0, 10_000_000, 0, decimal("1")).target()).isEqualByComparingTo("1.40");
    }

    @Test
    void emptyAndFractionalSupplyUseADenominatorOfAtLeastOne() {
        assertThat(calculator.calculate(0, 0, 0, 0, decimal("1")).multiplier()).isEqualByComparingTo("1.00");
        assertThat(calculator.calculate(0, 4, 0, 1, decimal("1")).target()).isEqualByComparingTo("1.00");
        assertThat(calculator.calculate(1, 0, 0, 0, decimal("1")).target()).isEqualByComparingTo("1.40");
    }

    @Test
    void dampingAppliesInBothDirectionsWithoutExceedingTheAbsoluteBounds() {
        assertThat(calculator.calculate(100, 0, 1, 0, decimal("1")).multiplier()).isEqualByComparingTo("1.20");
        assertThat(calculator.calculate(0, 0, 1, 0, decimal("2")).multiplier()).isEqualByComparingTo("1.80");
        assertThat(calculator.calculate(100, 0, 1, 0, decimal("1.90")).multiplier()).isEqualByComparingTo("2.00");
        assertThat(calculator.calculate(0, 0, 1, 0, decimal("1.10")).multiplier()).isEqualByComparingTo("1.00");
    }

    @Test
    void largeCountsRemainExactWithoutLongOrFloatingPointOverflow() {
        var result = calculator.calculate(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, decimal("1"));
        assertThat(result.demand()).isEqualByComparingTo("11068046444225730968.40");
        assertThat(result.supply()).isEqualByComparingTo("13835058055282163710.50");
        assertThat(result.multiplier()).isEqualByComparingTo("1.00");
    }

    @Test
    void invalidCountsPreviousValuesAndStepTablesAreRejected() {
        assertThatThrownBy(() -> calculator.calculate(-1, 0, 0, 0, decimal("1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(0, -1, 0, 0, decimal("1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(0, 0, -1, 0, decimal("1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(0, 0, 0, -1, decimal("1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(0, 0, 0, 0, decimal("2.01"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(0, 0, 0, 0, decimal("0.99"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> step("1", "1.001")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> step("-0.01", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> step("1000", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> step("0.001", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ComputedSurgeCalculator(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ComputedSurgeCalculator(List.of(step("1", "1")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ComputedSurgeCalculator(List.of(step("0", "1.1")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ComputedSurgeCalculator(List.of(step("0", "1"), step("1", "1.2"), step("1.00", "1.3"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ComputedSurgeCalculator(List.of(step("0", "1"), step("1", "1.5"), step("2", "1.2"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unorderedConfigurationIsSortedAndCanonicalized() {
        var sorted = new ComputedSurgeCalculator(List.of(step("2", "2"), step("0", "1"), step("1", "1.5")));
        assertThat(sorted.calculate(2, 0, 1, 0, decimal("1.8")).multiplier()).isEqualTo(decimal("2.00"));
    }

    private static Step step(String ratio, String multiplier) {
        return new Step(decimal(ratio), decimal(multiplier));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
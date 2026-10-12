package com.ridehailing.pricing.app;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class ComputedSurgeCalculator {

    private static final BigDecimal QUOTE_WEIGHT = new BigDecimal("0.20");
    private static final BigDecimal NEIGHBOR_WEIGHT = new BigDecimal("0.50");
    private static final BigDecimal MAX_CHANGE = new BigDecimal("0.20");
    private final List<Step> steps;

    public ComputedSurgeCalculator(List<Step> steps) {
        this.steps = List.copyOf(steps).stream().sorted(Comparator.comparing(Step::ratioFrom)).toList();
        if (this.steps.isEmpty() || this.steps.getFirst().ratioFrom().signum() != 0
                || this.steps.getFirst().multiplier().compareTo(SurgeWindows.NONE) != 0) {
            throw new IllegalArgumentException("Surge steps must start at ratio 0 with multiplier 1.00");
        }
        for (int index = 1; index < this.steps.size(); index++) {
            Step previous = this.steps.get(index - 1);
            Step current = this.steps.get(index);
            if (current.ratioFrom().compareTo(previous.ratioFrom()) <= 0
                    || current.multiplier().compareTo(previous.multiplier()) < 0) {
                throw new IllegalArgumentException("Surge thresholds must be unique and multipliers nondecreasing");
            }
        }
    }

    public Result calculate(long rides, long quotes, long available, long neighboring, BigDecimal previous) {
        if (rides < 0 || quotes < 0 || available < 0 || neighboring < 0) {
            throw new IllegalArgumentException("Surge counts must be nonnegative");
        }
        previous = multiplier(previous);
        BigDecimal demand = BigDecimal.valueOf(rides).add(BigDecimal.valueOf(quotes).multiply(QUOTE_WEIGHT));
        BigDecimal supply = BigDecimal.valueOf(available).add(BigDecimal.valueOf(neighboring).multiply(NEIGHBOR_WEIGHT));
        BigDecimal denominator = supply.max(BigDecimal.ONE);
        BigDecimal target = SurgeWindows.NONE;
        for (Step step : steps) {
            if (demand.compareTo(step.ratioFrom().multiply(denominator)) < 0) {
                break;
            }
            target = step.multiplier();
        }
        BigDecimal next = target.max(previous.subtract(MAX_CHANGE)).min(previous.add(MAX_CHANGE))
                .max(SurgeWindows.NONE).min(SurgeWindows.CAP);
        return new Result(next, target, demand, supply);
    }

    private static BigDecimal multiplier(BigDecimal value) {
        Objects.requireNonNull(value, "multiplier");
        if (value.compareTo(SurgeWindows.NONE) < 0 || value.compareTo(SurgeWindows.CAP) > 0
                || value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Surge multipliers must be 1.00..2.00 with at most two decimal places");
        }
        return value.setScale(2);
    }

    public record Step(BigDecimal ratioFrom, BigDecimal multiplier) {
        public Step {
            Objects.requireNonNull(ratioFrom, "ratioFrom");
            if (ratioFrom.signum() < 0 || ratioFrom.compareTo(new BigDecimal("999.99")) > 0
                    || ratioFrom.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("Surge thresholds must be 0..999.99 with at most two decimal places");
            }
            ratioFrom = ratioFrom.setScale(2);
            multiplier = ComputedSurgeCalculator.multiplier(multiplier);
        }
    }

    public record Result(BigDecimal multiplier, BigDecimal target, BigDecimal demand, BigDecimal supply) {
    }
}
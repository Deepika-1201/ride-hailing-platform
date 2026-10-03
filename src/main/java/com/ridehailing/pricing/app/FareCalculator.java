package com.ridehailing.pricing.app;

import com.ridehailing.pricing.db.Fare;
import com.ridehailing.pricing.db.RuleRepository.FareRule;
import java.math.BigDecimal;

/**
 * The fare of LLD §10.2: integer paise, each product rounded half up to the paisa, and the total rounded up to whole
 * rupees once, at the end (FR-PR2).
 */
public final class FareCalculator {

    private FareCalculator() {
    }

    /** {@code multiplier} has at most two decimals, from 1.00 to 2.00. */
    public static Fare calculate(FareRule rule, int distanceM, int durationS, BigDecimal multiplier) {
        long surgeHundredths = multiplier.movePointRight(2).intValueExact() - 100L;
        long distance = halfUp(Math.multiplyExact(rule.perKmPaise(), distanceM), 1_000);
        long time = halfUp(Math.multiplyExact(rule.perMinPaise(), durationS), 60);
        long preSurge = rule.basePaise() + distance + time;
        long surge = halfUp(Math.multiplyExact(preSurge, surgeHundredths), 100);
        long minimumTopup = Math.max(0, rule.minimumPaise() - (preSurge + surge));
        long beforeTax = preSurge + surge + minimumTopup + rule.bookingFeePaise();
        long tax = halfUp(Math.multiplyExact(beforeTax, rule.taxBp()), 10_000);
        long total = Math.ceilDiv(beforeTax + tax, 100) * 100;
        long commission = halfUp(Math.multiplyExact(total - tax, rule.commissionBp()), 10_000);
        return new Fare(rule.basePaise(), distance, time, surge, minimumTopup, rule.bookingFeePaise(), tax,
                total - (beforeTax + tax), total, commission);
    }

    // For a non-negative numerator.
    private static long halfUp(long numerator, long denominator) {
        return (2 * numerator + denominator) / (2 * denominator);
    }
}

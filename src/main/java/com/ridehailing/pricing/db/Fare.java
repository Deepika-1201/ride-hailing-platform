package com.ridehailing.pricing.db;

/**
 * A quote's fare in paise, as stored (LLD §4.4): the components add up to {@code total}; {@code commission} is the
 * platform's share of the fare before tax.
 */
public record Fare(long base, long distance, long time, long surge, long minimumTopup, long bookingFee, long tax,
        long rounding, long total, long commission) {
}

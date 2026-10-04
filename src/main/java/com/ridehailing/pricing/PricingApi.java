package com.ridehailing.pricing;

import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Upfront fares (LLD §10): quotes for riders, and their one-time use by a booking (§7.2). */
public interface PricingApi {

    /** A new quote, valid for {@code ride.quotes.ttl}; {@code 422} if the trip can't be priced. */
    QuoteView quote(UUID riderId, QuoteRequest request);

    /**
     * Marks the rider's quote used by the ride, in the caller's transaction: {@code 404} if it's missing or another
     * rider's, {@code 409 QUOTE_EXPIRED} or {@code 409 QUOTE_ALREADY_USED}.
     */
    ConsumedQuote consume(UUID quoteId, UUID riderId, UUID rideId);

    /** A fee rule version, such as the one a ride fixed at booking (LLD §7.4); versions are never changed. */
    FeeTerms feeRule(UUID feeRuleId);

    record QuoteRequest(GeoPoint pickup, GeoPoint dropoff, String category) {
    }

    /** {@code pickupEtaS} is null when no driver is near. */
    record QuoteView(UUID id, String cityId, String category, GeoPoint pickup, GeoPoint dropoff, int distanceM,
            int durationS, FareBreakdown fare, BigDecimal surgeMultiplier, Integer pickupEtaS, Instant createdAt,
            Instant expiresAt) {
    }

    record FareBreakdown(Money base, Money distance, Money time, Money surge, Money minimumTopup, Money bookingFee,
            Money tax, Money rounding, Money total) {
    }

    /** What a ride keeps from its quote. */
    record ConsumedQuote(UUID quoteId, String cityId, String category, GeoPoint pickup, GeoPoint dropoff,
            String pickupZone, int distanceM, int durationS, Money fare, Money commission, UUID feeRuleId) {
    }

    /** The cancellation and no-show terms of a fee rule version. */
    record FeeTerms(UUID id, long cancellationFeePaise, long noShowFeePaise, Duration freeCancelWindow,
            Duration lateGrace, Duration pickupWait, int commissionBp, String currency) {
    }
}

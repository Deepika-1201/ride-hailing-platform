package com.ridehailing.ride.app;

import com.ridehailing.platform.ApiException;
import com.ridehailing.pricing.PricingApi.FeeTerms;
import com.ridehailing.ride.RideOperations.FeeRequest;
import com.ridehailing.ride.RideStatus;
import com.ridehailing.ride.db.RideRepository.RideRow;
import com.ridehailing.ride.events.RideCancelled;
import com.ridehailing.shared.Money;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * The fee decisions of ride lifecycle §5 (LLD §7.4), as functions of the ride's stored times, the fee rule fixed at
 * booking, and the database's time, so they can be tested at their exact boundaries.
 */
final class Fees {

    static final String CANCELLATION_FEE = "CANCELLATION_FEE";
    static final String NO_SHOW_FEE = "NO_SHOW_FEE";

    private Fees() {
    }

    /** T8: free within the window after assignment, and free when the driver is late. */
    static Optional<RideFee> onRiderCancel(RideRow ride, FeeTerms rule, Instant now) {
        if (ride.status() == RideStatus.SEARCHING) {
            return Optional.empty();
        }
        if (!now.isAfter(ride.assignedAt().plus(rule.freeCancelWindow()))) {
            return Optional.empty();
        }
        Instant lateAfter = ride.assignedAt().plusSeconds(ride.promisedPickupEtaS()).plus(rule.lateGrace());
        boolean driverLate = now.isAfter(lateAfter)
                && (ride.arrivedAt() == null || ride.arrivedAt().isAfter(lateAfter));
        if (driverLate) {
            return Optional.empty();
        }
        return fee(CANCELLATION_FEE, rule.cancellationFeePaise(), rule);
    }

    /** T10: when the driver may end the ride as a no-show. */
    static Instant noShowFrom(RideRow ride, FeeTerms rule) {
        return ride.arrivedAt().plus(rule.pickupWait());
    }

    /** T10 is allowed from the end of the pickup wait, inclusive. */
    static boolean noShowAllowed(RideRow ride, FeeTerms rule, Instant now) {
        return !now.isBefore(noShowFrom(ride, rule));
    }

    static Optional<RideFee> onNoShow(FeeTerms rule) {
        return fee(NO_SHOW_FEE, rule.noShowFeePaise(), rule);
    }

    /** T13: the fee operations chose, at most the rule's amount for its purpose. */
    static Optional<RideFee> chosenByOperations(Optional<FeeRequest> request, FeeTerms rule) {
        if (request.isEmpty()) {
            return Optional.empty();
        }
        FeeRequest chosen = request.get();
        long limit = chosen.purpose().equals(NO_SHOW_FEE) ? rule.noShowFeePaise() : rule.cancellationFeePaise();
        if (chosen.amountPaise() > limit) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "FEE_EXCEEDS_RULE",
                    "The fee is more than the ride's fee rule allows (" + limit + " paise).");
        }
        return fee(chosen.purpose(), chosen.amountPaise(), rule);
    }

    private static Optional<RideFee> fee(String purpose, long amountPaise, FeeTerms rule) {
        if (amountPaise == 0) {
            return Optional.empty();
        }
        long commission = (Math.multiplyExact(amountPaise, rule.commissionBp()) + 5_000) / 10_000;
        return Optional.of(new RideFee(purpose, amountPaise, commission, rule.id(), rule.currency()));
    }

    /** A fee on the ride: its amount and commission are paise; the commission is rounded half up. */
    record RideFee(String purpose, long amountPaise, long commissionPaise, UUID feeRuleId, String currency) {

        RideCancelled.Fee event() {
            return new RideCancelled.Fee(purpose, new Money(amountPaise, currency),
                    new Money(commissionPaise, currency), feeRuleId);
        }
    }
}

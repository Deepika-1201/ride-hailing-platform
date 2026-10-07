package com.ridehailing.ride.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ridehailing.platform.ApiException;
import com.ridehailing.pricing.PricingApi.FeeTerms;
import com.ridehailing.ride.RideOperations.FeeRequest;
import com.ridehailing.ride.RideStatus;
import com.ridehailing.ride.app.Fees.RideFee;
import com.ridehailing.ride.db.RideRepository.RideRow;
import com.ridehailing.shared.GeoPoint;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Ride lifecycle §5 at its exact boundaries (LLD §7.4, §7.7). */
class FeesTests {

    private static final Instant ASSIGNED = Instant.parse("2026-10-03T10:00:00Z");
    private static final int ETA_S = 300;
    /** Every duration differs, so a rule that reads the wrong one fails. */
    private static final FeeTerms RULE = new FeeTerms(UUID.randomUUID(), 5_000, 7_500, Duration.ofSeconds(120),
            Duration.ofSeconds(300), Duration.ofSeconds(240), 2_000, "INR");
    private static final Instant LATE_AFTER = ASSIGNED.plusSeconds(ETA_S + 300);

    @Test
    void cancellingIsFreeUpToTheEndOfTheWindowInclusive() {
        assertThat(Fees.onRiderCancel(assigned(null), RULE, ASSIGNED.plusSeconds(120))).isEmpty();
        assertThat(Fees.onRiderCancel(assigned(null), RULE, ASSIGNED.plusSeconds(120).plusNanos(1000)))
                .map(RideFee::amountPaise).hasValue(5_000L);
    }

    @Test
    void aDriverWhoHasntArrivedByTheLateMarkMakesItFree() {
        assertThat(Fees.onRiderCancel(assigned(null), RULE, LATE_AFTER)).as("at the mark: not late yet").isPresent();
        assertThat(Fees.onRiderCancel(assigned(null), RULE, LATE_AFTER.plusNanos(1000))).isEmpty();
        assertThat(Fees.onRiderCancel(assigned(LATE_AFTER.plusSeconds(1)), RULE, LATE_AFTER.plusSeconds(60)))
                .as("arrived after the mark").isEmpty();
    }

    @Test
    void aDriverWhoArrivedByTheLateMarkKeepsTheFee() {
        assertThat(Fees.onRiderCancel(assigned(LATE_AFTER), RULE, LATE_AFTER.plusSeconds(600))).isPresent();
        assertThat(Fees.onRiderCancel(assigned(LATE_AFTER.minusSeconds(100)), RULE, LATE_AFTER.plusSeconds(1)))
                .isPresent();
    }

    @Test
    void cancellingWhileSearchingIsAlwaysFree() {
        RideRow searching = ride(RideStatus.SEARCHING, null, null);

        assertThat(Fees.onRiderCancel(searching, RULE, ASSIGNED.plusSeconds(3_600))).isEmpty();
    }

    @Test
    void theCommissionIsTheRulesShareRoundedHalfUp() {
        FeeTerms threeQuarters = new FeeTerms(RULE.id(), 5_000, 7_500, RULE.freeCancelWindow(), RULE.lateGrace(),
                RULE.pickupWait(), 2_001, "INR");
        FeeTerms half = new FeeTerms(RULE.id(), 4, 7_500, RULE.freeCancelWindow(), RULE.lateGrace(),
                RULE.pickupWait(), 1_250, "INR");

        assertThat(Fees.onNoShow(threeQuarters)).as("1500.75").map(RideFee::commissionPaise).hasValue(1_501L);
        assertThat(Fees.onRiderCancel(assigned(null), half, ASSIGNED.plusSeconds(200))).as("0.5")
                .map(RideFee::commissionPaise).hasValue(1L);
        assertThat(Fees.onNoShow(half)).as("937.5").map(RideFee::commissionPaise).hasValue(938L);
    }

    @Test
    void aRuleAmountOfZeroMeansNoFee() {
        FeeTerms free = new FeeTerms(RULE.id(), 0, 0, RULE.freeCancelWindow(), RULE.lateGrace(), RULE.pickupWait(),
                RULE.commissionBp(), "INR");

        assertThat(Fees.onRiderCancel(assigned(null), free, ASSIGNED.plusSeconds(200))).isEmpty();
        assertThat(Fees.onNoShow(free)).isEmpty();
    }

    @Test
    void theNoShowOpensAfterThePickupWait() {
        RideRow arrived = ride(RideStatus.DRIVER_ARRIVED, ASSIGNED, ASSIGNED.plusSeconds(100));
        Instant opens = ASSIGNED.plusSeconds(100 + 240);

        assertThat(Fees.noShowFrom(arrived, RULE)).isEqualTo(opens);
        assertThat(Fees.noShowAllowed(arrived, RULE, opens.minusNanos(1000))).isFalse();
        assertThat(Fees.noShowAllowed(arrived, RULE, opens)).as("at the end of the wait").isTrue();
        assertThat(Fees.onNoShow(RULE)).map(RideFee::purpose).hasValue(Fees.NO_SHOW_FEE);
    }

    @Test
    void operationsChooseAFeeUpToTheRuleForItsPurpose() {
        assertThat(Fees.chosenByOperations(Optional.empty(), RULE)).isEmpty();
        assertThat(Fees.chosenByOperations(Optional.of(new FeeRequest(Fees.CANCELLATION_FEE, 5_000)), RULE))
                .map(RideFee::amountPaise).hasValue(5_000L);
        assertThat(Fees.chosenByOperations(Optional.of(new FeeRequest(Fees.NO_SHOW_FEE, 7_500)), RULE))
                .map(RideFee::amountPaise).hasValue(7_500L);
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> Fees.chosenByOperations(Optional.of(new FeeRequest(Fees.CANCELLATION_FEE, 5_001)),
                        RULE))
                .satisfies(e -> assertThat(e.code()).isEqualTo("FEE_EXCEEDS_RULE"));
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> Fees.chosenByOperations(Optional.of(new FeeRequest(Fees.NO_SHOW_FEE, 7_501)), RULE));
    }

    private static RideRow assigned(Instant arrivedAt) {
        return ride(arrivedAt == null ? RideStatus.DRIVER_ASSIGNED : RideStatus.DRIVER_ARRIVED, ASSIGNED, arrivedAt);
    }

    private static RideRow ride(RideStatus status, Instant assignedAt, Instant arrivedAt) {
        GeoPoint here = new GeoPoint(12.97, 77.59);
        return new RideRow(UUID.randomUUID(), UUID.randomUUID(), "blr", "MINI", UUID.randomUUID(), here, here, "z",
                20_000, "INR", UUID.randomUUID(), "CASH", status, 2, 1, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "{}", "{}", "1234", ETA_S, ASSIGNED.minusSeconds(60), assignedAt, arrivedAt, null,
                null, null, null, null, 0, 4_000, RULE.id(), 0, null, null, 5_000, 600, null);
    }
}

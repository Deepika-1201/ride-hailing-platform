package com.ridehailing.ride;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.rating.RatingApi.RatingSummary;
import com.ridehailing.ride.RideView.PersonSummary;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** FR-RD5: the PIN is the rider's alone, from assignment until the trip starts. */
class RideViewTests {

    @ParameterizedTest
    @EnumSource(RideStatus.class)
    void theRiderSeesThePinOnlyWhileTheDriverIsOnTheWay(RideStatus status) {
        boolean onTheWay = status == RideStatus.DRIVER_ASSIGNED || status == RideStatus.DRIVER_ARRIVED;

        assertThat(ride(status).forRider().pin()).isEqualTo(onTheWay ? "4821" : null);
    }

    @ParameterizedTest
    @EnumSource(RideStatus.class)
    void theDriverNeverSeesThePin(RideStatus status) {
        assertThat(ride(status).forDriver().pin()).isNull();
    }

    @ParameterizedTest
    @EnumSource(RideStatus.class)
    void onlyTheDriverSeesTheRiderSummary(RideStatus status) {
        assertThat(ride(status).forRider().rider()).isNull();
        assertThat(ride(status).forDriver().rider()).isEqualTo(ASHA);
    }

    private static final UUID RIDER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final PersonSummary ASHA = new PersonSummary(RIDER, "Asha",
            new RatingSummary(new BigDecimal("4.75"), 12));

    private static RideView ride(RideStatus status) {
        return new RideView(UUID.randomUUID(), status, 1, "BLR", "MINI", null, null, null, null, "4821", null, null,
                null, ASHA, null, null, null, null, null, null, null, RIDER, null, null);
    }
}

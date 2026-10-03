package com.ridehailing.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** LLD §1.4: haversine distances with an Earth radius of 6,371,008.8 m. */
class GeoPointTests {

    @Test
    void aDegreeOfLatitudeIsAbout111Kilometres() {
        assertThat(new GeoPoint(0, 0).metresTo(new GeoPoint(1, 0))).isCloseTo(111_195.08, within(0.01));
    }

    @Test
    void distancesAreSymmetricAndZeroToItself() {
        GeoPoint indiranagar = new GeoPoint(12.97194, 77.64115);
        GeoPoint koramangala = new GeoPoint(12.93524, 77.62448);

        assertThat(indiranagar.metresTo(koramangala)).isEqualTo(koramangala.metresTo(indiranagar));
        assertThat(indiranagar.metresTo(indiranagar)).isZero();
    }
}

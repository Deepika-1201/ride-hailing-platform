package com.ridehailing.shared;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/** A WGS 84 position, as JSON {@code {"lat": …, "lon": …}} (LLD §1.4); requests are checked with {@code @Valid}. */
public record GeoPoint(
        @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
        @DecimalMin("-180.0") @DecimalMax("180.0") double lon) {

    private static final double EARTH_RADIUS_M = 6_371_008.8;

    /** The great-circle (haversine) distance in metres. */
    public double metresTo(GeoPoint other) {
        double dLat = Math.toRadians(other.lat - lat);
        double dLon = Math.toRadians(other.lon - lon);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(other.lat)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}

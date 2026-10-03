package com.ridehailing.shared;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/** A WGS 84 position, as JSON {@code {"lat": …, "lon": …}} (LLD §1.4); requests are checked with {@code @Valid}. */
public record GeoPoint(
        @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
        @DecimalMin("-180.0") @DecimalMax("180.0") double lon) {
}

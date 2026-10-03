package com.ridehailing.geography.app;

import com.ridehailing.shared.GeoPoint;
import com.uber.h3core.H3Core;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.stereotype.Component;

/** H3 resolution-7 cells (about 5 km²), the pricing zone outside special areas (ADR-012, LLD §10.1). */
@Component
class H3Zones {

    static final int RESOLUTION = 7;

    private final H3Core h3;

    H3Zones() {
        try {
            h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load the H3 native library", e);
        }
    }

    String cellOf(GeoPoint point) {
        return h3.latLngToCellAddress(point.lat(), point.lon(), RESOLUTION);
    }

    boolean isCell(String zoneId) {
        try {
            return h3.isValidCell(zoneId) && h3.getResolution(zoneId) == RESOLUTION;
        } catch (IllegalArgumentException e) {
            // Not hexadecimal at all.
            return false;
        }
    }
}

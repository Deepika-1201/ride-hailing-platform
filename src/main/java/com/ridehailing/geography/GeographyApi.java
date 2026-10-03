package com.ridehailing.geography;

import com.ridehailing.shared.GeoPoint;
import java.time.ZoneId;
import java.util.Optional;

/** Cities, service areas and pricing zones, for the other modules (LLD §2.2, §10.1). */
public interface GeographyApi {

    Optional<CityView> city(String cityId);

    /** Whether the city is active and offers the category. */
    boolean offers(String cityId, String category);

    /** The service area containing the point, with its zone; empty outside every service area. */
    Optional<Location> locate(GeoPoint point);

    /** An H3 resolution-7 cell, or {@code area:<code>} of an active special area in the city. */
    boolean isZone(String cityId, String zoneId);

    record CityView(String id, String name, ZoneId timeZone, String currency, boolean active) {
    }

    /** {@code zoneId} is {@code area:<code>} inside a special area, otherwise the point's H3 resolution-7 cell. */
    record Location(String cityId, String zoneId) {
    }
}

package com.ridehailing.shared;

/** A latitude and longitude box, such as a city's bounds for validating locations. */
public record BoundingBox(double minLat, double minLon, double maxLat, double maxLon) {

    public boolean contains(GeoPoint point) {
        return point.lat() >= minLat && point.lat() <= maxLat && point.lon() >= minLon && point.lon() <= maxLon;
    }

    /** The midpoint of the latitudes and of the longitudes; boxes don't cross the antimeridian. */
    public GeoPoint centre() {
        return new GeoPoint((minLat + maxLat) / 2, (minLon + maxLon) / 2);
    }
}

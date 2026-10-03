package com.ridehailing.shared;

/** A latitude and longitude box, such as a city's bounds for validating locations. */
public record BoundingBox(double minLat, double minLon, double maxLat, double maxLon) {

    public boolean contains(GeoPoint point) {
        return point.lat() >= minLat && point.lat() <= maxLat && point.lon() >= minLon && point.lon() <= maxLon;
    }
}

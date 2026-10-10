package com.ridehailing.realtime.ws;

import com.ridehailing.location.LiveIndex.LocationUpdate;
import com.ridehailing.shared.BoundingBox;
import com.ridehailing.shared.GeoPoint;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Reads clients' messages by {@code client-messages.v1.json}'s rules; null means a message breaks them. */
final class Inbound {

    private static final Pattern CITY = Pattern.compile("[a-z]{3,8}");

    private Inbound() {
    }

    static LocationUpdate location(JsonNode message) {
        JsonNode seq = message.get("seq");
        Double lat = number(message, "lat", -90, 90);
        Double lon = number(message, "lon", -180, 180);
        Double accuracyM = number(message, "accuracy_m", 0, Double.MAX_VALUE);
        Instant deviceTime = instant(message.get("device_time"));
        if (seq == null || !seq.isIntegralNumber() || !seq.canConvertToLong() || seq.asLong() < 0 || lat == null
                || lon == null || accuracyM == null || deviceTime == null) {
            return null;
        }
        JsonNode heading = message.get("heading_deg");
        JsonNode speed = message.get("speed_mps");
        Double headingDeg = absent(heading) ? null : number(message, "heading_deg", 0, Math.nextDown(360.0));
        Double speedMps = absent(speed) ? null : number(message, "speed_mps", 0, Double.MAX_VALUE);
        if (!absent(heading) && headingDeg == null || !absent(speed) && speedMps == null) {
            return null;
        }
        return new LocationUpdate(seq.asLong(), new GeoPoint(lat, lon), accuracyM, headingDeg, speedMps, deviceTime);
    }

    static UUID uuid(JsonNode message, String field) {
        JsonNode value = message.get(field);
        if (value == null || !value.isString()) {
            return null;
        }
        try {
            return UUID.fromString(value.asString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Session.Viewport viewport(JsonNode message) {
        JsonNode city = message.get("city_id");
        JsonNode bbox = message.get("bbox");
        if (city == null || !city.isString() || !CITY.matcher(city.asString()).matches() || bbox == null
                || !bbox.isObject()) {
            return null;
        }
        Double minLat = number(bbox, "min_lat", -90, 90);
        Double minLon = number(bbox, "min_lon", -180, 180);
        Double maxLat = number(bbox, "max_lat", -90, 90);
        Double maxLon = number(bbox, "max_lon", -180, 180);
        if (minLat == null || minLon == null || maxLat == null || maxLon == null || minLat > maxLat
                || minLon > maxLon) {
            return null;
        }
        return new Session.Viewport(city.asString(), new BoundingBox(minLat, minLon, maxLat, maxLon));
    }

    private static Double number(JsonNode message, String field, double min, double max) {
        JsonNode value = message.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < min
                || value.asDouble() > max) {
            return null;
        }
        return value.asDouble();
    }

    private static Instant instant(JsonNode value) {
        if (value == null || !value.isString()) {
            return null;
        }
        try {
            return Instant.parse(value.asString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean absent(JsonNode value) {
        return value == null || value.isNull();
    }
}

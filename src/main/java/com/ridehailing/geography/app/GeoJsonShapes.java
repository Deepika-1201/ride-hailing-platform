package com.ridehailing.geography.app;

import com.ridehailing.geography.db.SpatialQueries;
import com.ridehailing.platform.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checks a GeoJSON shape from a request: its declared type first, then that PostGIS finds it valid (LLD §4.4). */
@Component
class GeoJsonShapes {

    private final SpatialQueries spatial;
    private final JsonMapper json;

    GeoJsonShapes(SpatialQueries spatial, JsonMapper json) {
        this.spatial = spatial;
        this.json = json;
    }

    /** Returns the shape as GeoJSON text; {@code type} is {@code Polygon} or {@code MultiPolygon}. */
    String require(JsonNode shape, String field, String type) {
        if (shape == null || !shape.isObject() || !type.equals(shape.path("type").asString(""))
                || !shape.path("coordinates").isArray()) {
            throw ApiException.invalid(field, "must be a GeoJSON " + type);
        }
        String geoJson = json.writeValueAsString(shape);
        if (!spatial.isValid(geoJson, type.toUpperCase())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_GEOMETRY",
                    "The " + field + " is not a valid " + type + ": rings must be closed and must not cross.");
        }
        return geoJson;
    }
}

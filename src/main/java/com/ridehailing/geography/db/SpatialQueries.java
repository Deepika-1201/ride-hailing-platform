package com.ridehailing.geography.db;

import com.ridehailing.shared.GeoPoint;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostGIS questions: is a GeoJSON shape valid, and which service and special area contain a point (LLD §10.1). */
@Repository
public class SpatialQueries {

    private final JdbcClient jdbc;

    SpatialQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True if PostGIS parses the GeoJSON as a valid geometry of the type, such as {@code POLYGON}. */
    public boolean isValid(String geoJson, String geometryType) {
        try {
            return jdbc.sql("""
                            SELECT ST_IsValid(g) AND GeometryType(g) = :type
                            FROM (SELECT ST_GeomFromGeoJSON(:geoJson) AS g) AS parsed
                            """)
                    .param("type", geometryType)
                    .param("geoJson", geoJson)
                    .query(Boolean.class)
                    .single();
        } catch (DataAccessException e) {
            // PostGIS refuses to parse it, for example an unclosed ring.
            return false;
        }
    }

    /** The city whose active service area covers the point, and the highest-priority special area there, if any. */
    public Optional<Containing> containing(GeoPoint point) {
        return jdbc.sql("""
                        SELECT sa.city_id,
                               (SELECT sp.code FROM geography.special_areas sp
                                 WHERE sp.city_id = sa.city_id AND sp.active AND ST_Covers(sp.area, p.pt)
                                 ORDER BY sp.priority DESC, sp.code LIMIT 1) AS special_area
                          FROM (SELECT ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS pt) p
                          JOIN geography.service_areas sa ON sa.active AND ST_Covers(sa.area, p.pt)
                         LIMIT 1
                        """)
                .param("lon", point.lon())
                .param("lat", point.lat())
                .query((row, rowNumber) -> new Containing(row.getString("city_id"), row.getString("special_area")))
                .optional();
    }

    /** {@code specialArea} is null outside every special area. */
    public record Containing(String cityId, String specialArea) {
    }
}

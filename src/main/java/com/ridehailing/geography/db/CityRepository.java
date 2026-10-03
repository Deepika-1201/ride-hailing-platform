package com.ridehailing.geography.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cities and their service areas; geometries go in and out as GeoJSON text. */
@Repository
public class CityRepository {

    private static final String COLUMNS = """
            SELECT id, name, time_zone, currency, ST_AsGeoJSON(bounds) AS bounds, active, version
            FROM geography.cities
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    CityRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** False if a city with this ID exists already. */
    public boolean insert(String id, String name, String timeZone, String currency, String boundsGeoJson) {
        return jdbc.sql("""
                        INSERT INTO geography.cities (id, name, time_zone, currency, bounds)
                        VALUES (:id, :name, :timeZone, :currency, ST_GeomFromGeoJSON(:bounds))
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", id)
                .param("name", name)
                .param("timeZone", timeZone)
                .param("currency", currency)
                .param("bounds", boundsGeoJson)
                .update() == 1;
    }

    public Optional<CityRow> find(String id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id").param("id", id).query(this::city).optional();
    }

    /** Locks the city, so changes to its areas take turns. */
    public Optional<CityRow> lock(String id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id FOR UPDATE").param("id", id).query(this::city).optional();
    }

    public List<CityRow> list() {
        return jdbc.sql(COLUMNS + "ORDER BY id").query(this::city).list();
    }

    /** Applies the changes only at the expected version; empty if the version moved on or the city is unknown. */
    public Optional<CityRow> update(String id, int expectedVersion, String name, Boolean active) {
        return jdbc.sql("""
                        UPDATE geography.cities
                        SET name = coalesce(:name, name), active = coalesce(:active, active), version = version + 1
                        WHERE id = :id AND version = :version
                        RETURNING id, name, time_zone, currency, ST_AsGeoJSON(bounds) AS bounds, active, version
                        """)
                .param("name", name)
                .param("active", active)
                .param("id", id)
                .param("version", expectedVersion)
                .query(this::city)
                .optional();
    }

    /** Replaces the active service area; the caller holds the city's lock. */
    public void replaceServiceArea(String cityId, UUID id, String areaGeoJson) {
        jdbc.sql("UPDATE geography.service_areas SET active = false WHERE city_id = :cityId AND active")
                .param("cityId", cityId)
                .update();
        jdbc.sql("""
                        INSERT INTO geography.service_areas (id, city_id, area)
                        VALUES (:id, :cityId, ST_Multi(ST_GeomFromGeoJSON(:area)))
                        """)
                .param("id", id)
                .param("cityId", cityId)
                .param("area", areaGeoJson)
                .update();
    }

    private CityRow city(ResultSet row, int rowNumber) throws SQLException {
        return new CityRow(row.getString("id"), row.getString("name"), row.getString("time_zone"),
                row.getString("currency"), json.readTree(row.getString("bounds")), row.getBoolean("active"),
                row.getInt("version"));
    }

    public record CityRow(String id, String name, String timeZone, String currency, JsonNode bounds, boolean active,
            int version) {
    }
}

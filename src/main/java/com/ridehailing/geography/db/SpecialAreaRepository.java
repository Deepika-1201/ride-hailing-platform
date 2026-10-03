package com.ridehailing.geography.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Special areas: airports, stations and stadiums, which take precedence over H3 cells as zones (LLD §10.1). */
@Repository
public class SpecialAreaRepository {

    private static final String COLUMNS = """
            SELECT id, city_id, code, name, kind, ST_AsGeoJSON(area) AS area, priority, active
            FROM geography.special_areas
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    SpecialAreaRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** False if the code is taken, in any city. */
    public boolean insert(UUID id, String cityId, String code, String name, String kind, String areaGeoJson,
            int priority) {
        return jdbc.sql("""
                        INSERT INTO geography.special_areas (id, city_id, code, name, kind, area, priority)
                        VALUES (:id, :cityId, :code, :name, :kind, ST_Multi(ST_GeomFromGeoJSON(:area)), :priority)
                        ON CONFLICT (code) DO NOTHING
                        """)
                .param("id", id)
                .param("cityId", cityId)
                .param("code", code)
                .param("name", name)
                .param("kind", kind)
                .param("area", areaGeoJson)
                .param("priority", priority)
                .update() == 1;
    }

    public List<SpecialAreaRow> list(String cityId) {
        return jdbc.sql(COLUMNS + "WHERE city_id = :cityId ORDER BY code").param("cityId", cityId).query(this::area).list();
    }

    public SpecialAreaRow get(UUID id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id").param("id", id).query(this::area).single();
    }

    /** False if the city has no such area. */
    public boolean deactivate(String cityId, UUID id) {
        return jdbc.sql("UPDATE geography.special_areas SET active = false WHERE id = :id AND city_id = :cityId")
                .param("id", id)
                .param("cityId", cityId)
                .update() == 1;
    }

    public boolean activeCodeIn(String cityId, String code) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM geography.special_areas
                                       WHERE city_id = :cityId AND code = :code AND active)
                        """)
                .param("cityId", cityId)
                .param("code", code)
                .query(Boolean.class)
                .single();
    }

    private SpecialAreaRow area(ResultSet row, int rowNumber) throws SQLException {
        return new SpecialAreaRow(row.getObject("id", UUID.class), row.getString("city_id"), row.getString("code"),
                row.getString("name"), row.getString("kind"), json.readTree(row.getString("area")),
                row.getInt("priority"), row.getBoolean("active"));
    }

    public record SpecialAreaRow(UUID id, String cityId, String code, String name, String kind, JsonNode area,
            int priority, boolean active) {
    }
}

package com.ridehailing.geography.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Categories (reference data) and each city's settings for them (LLD §4.4). */
@Repository
public class CategoryRepository {

    private static final String SETTINGS = """
            SELECT city_id, category, active, offer_ttl_s, search_timeout_s, radius_start_m, radius_step_m,
                   radius_max_m, ranker, version
            FROM geography.city_categories
            """;

    private final JdbcClient jdbc;

    CategoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Category> categories() {
        return jdbc.sql("SELECT code, name, seats FROM geography.categories ORDER BY code")
                .query((row, rowNumber) -> new Category(row.getString("code"), row.getString("name"),
                        row.getInt("seats")))
                .list();
    }

    public boolean categoryExists(String code) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM geography.categories WHERE code = :code)")
                .param("code", code)
                .query(Boolean.class)
                .single();
    }

    public Optional<Settings> settings(String cityId, String category) {
        return jdbc.sql(SETTINGS + "WHERE city_id = :cityId AND category = :category")
                .param("cityId", cityId)
                .param("category", category)
                .query(CategoryRepository::settings)
                .optional();
    }

    /** Inserts the settings at version 0; false if they exist already. */
    public boolean insert(Settings settings) {
        return jdbc.sql("""
                        INSERT INTO geography.city_categories (city_id, category, active, offer_ttl_s, search_timeout_s,
                                                               radius_start_m, radius_step_m, radius_max_m, ranker)
                        VALUES (:cityId, :category, :active, :offerTtlS, :searchTimeoutS,
                                :radiusStartM, :radiusStepM, :radiusMaxM, :ranker)
                        ON CONFLICT (city_id, category) DO NOTHING
                        """)
                .params(params(settings))
                .update() == 1;
    }

    /** Applies the settings only at the expected version. */
    public Optional<Settings> update(Settings settings, int expectedVersion) {
        return jdbc.sql("""
                        UPDATE geography.city_categories
                        SET active = :active, offer_ttl_s = :offerTtlS, search_timeout_s = :searchTimeoutS,
                            radius_start_m = :radiusStartM, radius_step_m = :radiusStepM, radius_max_m = :radiusMaxM,
                            ranker = :ranker, version = version + 1
                        WHERE city_id = :cityId AND category = :category AND version = :expectedVersion
                        RETURNING city_id, category, active, offer_ttl_s, search_timeout_s, radius_start_m,
                                  radius_step_m, radius_max_m, ranker, version
                        """)
                .params(params(settings))
                .param("expectedVersion", expectedVersion)
                .query(CategoryRepository::settings)
                .optional();
    }

    private static Map<String, Object> params(Settings settings) {
        return Map.of("cityId", settings.cityId(), "category", settings.category(), "active", settings.active(),
                "offerTtlS", settings.offerTtlS(), "searchTimeoutS", settings.searchTimeoutS(),
                "radiusStartM", settings.radiusStartM(), "radiusStepM", settings.radiusStepM(),
                "radiusMaxM", settings.radiusMaxM(), "ranker", settings.ranker());
    }

    public boolean offers(String cityId, String category) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM geography.city_categories cc
                                       JOIN geography.cities c ON c.id = cc.city_id
                                       WHERE cc.city_id = :cityId AND cc.category = :category AND cc.active AND c.active)
                        """)
                .param("cityId", cityId)
                .param("category", category)
                .query(Boolean.class)
                .single();
    }

    private static Settings settings(ResultSet row, int rowNumber) throws SQLException {
        return new Settings(row.getString("city_id"), row.getString("category"), row.getBoolean("active"),
                row.getInt("offer_ttl_s"), row.getInt("search_timeout_s"), row.getInt("radius_start_m"),
                row.getInt("radius_step_m"), row.getInt("radius_max_m"), row.getString("ranker"), row.getInt("version"));
    }

    public record Category(String code, String name, int seats) {
    }

    public record Settings(String cityId, String category, boolean active, int offerTtlS, int searchTimeoutS,
            int radiusStartM, int radiusStepM, int radiusMaxM, String ranker, int version) {
    }
}

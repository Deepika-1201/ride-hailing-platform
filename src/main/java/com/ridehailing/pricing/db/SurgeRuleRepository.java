package com.ridehailing.pricing.db;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

/** Surge rules: a multiplier for a zone, on some days, in a local time window (LLD §10.3). */
@Repository
public class SurgeRuleRepository {

    private static final String COLUMNS = """
            id, city_id, zone_id, days_of_week, start_local, end_local, multiplier, active, version
            """;

    private final JdbcClient jdbc;

    SurgeRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public SurgeRule insert(SurgeRule rule, UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO pricing.surge_rules (id, city_id, zone_id, days_of_week, start_local, end_local,
                                                         multiplier, created_by)
                        VALUES (:id, :cityId, :zoneId, :days, :startLocal, :endLocal, :multiplier, :createdBy)
                        RETURNING""" + " " + COLUMNS)
                .param("id", rule.id())
                .param("cityId", rule.cityId())
                .param("zoneId", rule.zoneId())
                .param("days", new SqlArrayValue("int2", rule.daysOfWeek().toArray()))
                .param("startLocal", rule.startLocal())
                .param("endLocal", rule.endLocal())
                .param("multiplier", rule.multiplier())
                .param("createdBy", createdBy)
                .query(SurgeRuleRepository::rule)
                .single();
    }

    public List<SurgeRule> list(String cityId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM pricing.surge_rules WHERE city_id = :cityId ORDER BY zone_id, id")
                .param("cityId", cityId)
                .query(SurgeRuleRepository::rule)
                .list();
    }

    public List<SurgeRule> activeIn(String cityId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM pricing.surge_rules WHERE city_id = :cityId AND active")
                .param("cityId", cityId)
                .query(SurgeRuleRepository::rule)
                .list();
    }

    public Optional<SurgeRule> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM pricing.surge_rules WHERE id = :id")
                .param("id", id)
                .query(SurgeRuleRepository::rule)
                .optional();
    }

    /** Applies the changes only at the expected version; null fields stay as they are. */
    public Optional<SurgeRule> update(UUID id, int expectedVersion, BigDecimal multiplier, Boolean active) {
        return jdbc.sql("""
                        UPDATE pricing.surge_rules
                        SET multiplier = coalesce(CAST(:multiplier AS numeric), multiplier),
                            active = coalesce(CAST(:active AS boolean), active),
                            version = version + 1
                        WHERE id = :id AND version = :version
                        RETURNING""" + " " + COLUMNS)
                .param("multiplier", multiplier)
                .param("active", active)
                .param("id", id)
                .param("version", expectedVersion)
                .query(SurgeRuleRepository::rule)
                .optional();
    }

    private static SurgeRule rule(ResultSet row, int rowNumber) throws SQLException {
        Array days = row.getArray("days_of_week");
        List<Integer> daysOfWeek = Arrays.stream((Short[]) days.getArray()).map(Short::intValue).toList();
        return new SurgeRule(row.getObject("id", UUID.class), row.getString("city_id"), row.getString("zone_id"),
                daysOfWeek, row.getObject("start_local", LocalTime.class), row.getObject("end_local", LocalTime.class),
                row.getBigDecimal("multiplier"), row.getBoolean("active"), row.getInt("version"));
    }

    /** {@code daysOfWeek}: 1 is Monday. {@code endLocal} before {@code startLocal} wraps past midnight. */
    public record SurgeRule(UUID id, String cityId, String zoneId, List<Integer> daysOfWeek, LocalTime startLocal,
            LocalTime endLocal, BigDecimal multiplier, boolean active, int version) {
    }
}

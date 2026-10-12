package com.ridehailing.pricing.db;

import com.ridehailing.geography.GeographyApi.ZoneCount;
import com.ridehailing.platform.CityOwnership.Claim;
import com.ridehailing.pricing.app.ComputedSurgeCalculator;
import com.ridehailing.pricing.app.ComputedSurgeCalculator.Step;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ComputedSurgeRepository {

    public static final int MAX_ZONES = 10_000;
    private static final Demand NO_DEMAND = new Demand(0, 0);
    private static final ZoneCount NO_SUPPLY = new ZoneCount(0, 0);
    private final JdbcClient jdbc;
    private final JsonMapper json;

    ComputedSurgeRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public boolean due(String cityId) {
        return jdbc.sql("""
                SELECT NOT EXISTS (SELECT 1 FROM pricing.surge_runs
                    WHERE city_id = :city AND computed_at > clock_timestamp() - interval '60 seconds')
                """).param("city", cityId).query(Boolean.class).single();
    }

    public Observation observe(String cityId) {
        record Row(Instant sampledAt, String zone, long rides, long quotes) {
        }
        List<Row> rows = jdbc.sql("""
                WITH boundary AS MATERIALIZED (SELECT statement_timestamp() AS sampled_at), counts AS (
                    SELECT pickup_zone AS zone, 0::bigint AS rides, count(*) AS quotes
                    FROM pricing.quotes, boundary
                    WHERE city_id = :city AND created_at > sampled_at - interval '5 minutes' AND created_at <= sampled_at
                    GROUP BY pickup_zone
                    UNION ALL
                    SELECT pickup_zone AS zone, count(*) AS rides, 0::bigint AS quotes
                    FROM pricing.quotes, boundary
                    WHERE city_id = :city AND used_at > sampled_at - interval '5 minutes' AND used_at <= sampled_at
                    GROUP BY pickup_zone
                ), grouped AS (
                    SELECT zone, sum(rides)::bigint AS rides, sum(quotes)::bigint AS quotes FROM counts GROUP BY zone
                    ORDER BY zone LIMIT :limit
                )
                SELECT boundary.sampled_at, grouped.zone, grouped.rides, grouped.quotes
                FROM boundary LEFT JOIN grouped ON true
                """).param("city", cityId).param("limit", MAX_ZONES + 1)
                .query((row, rowNumber) -> new Row(row.getObject("sampled_at", OffsetDateTime.class).toInstant(),
                        row.getString("zone"), row.getLong("rides"), row.getLong("quotes"))).list();
        if (rows.size() > MAX_ZONES) {
            throw new IllegalStateException("Surge demand exceeds the zone limit");
        }
        Map<String, Demand> demand = new HashMap<>();
        rows.stream().filter(row -> row.zone() != null).forEach(row -> demand.put(row.zone(), new Demand(row.rides(), row.quotes())));
        return new Observation(rows.getFirst().sampledAt(), Map.copyOf(demand));
    }

    public boolean commit(Claim claim, Observation observation, Map<String, ZoneCount> supply, List<Step> steps) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Computed surge publication requires an existing fenced transaction");
        }
        ComputedSurgeCalculator calculator = new ComputedSurgeCalculator(steps);
        Optional<OffsetDateTime> computedAt = jdbc.sql("""
                INSERT INTO pricing.surge_runs (city_id, sampled_at, computed_at, owner_holder, owner_token)
                SELECT :city, :sampledAt, clock_timestamp(), :holder, :token
                WHERE CAST(:sampledAt AS timestamptz) <= clock_timestamp()
                  AND CAST(:sampledAt AS timestamptz) > clock_timestamp() - interval '10 seconds'
                ON CONFLICT (city_id) DO UPDATE SET sampled_at = EXCLUDED.sampled_at, computed_at = EXCLUDED.computed_at,
                    owner_holder = EXCLUDED.owner_holder, owner_token = EXCLUDED.owner_token
                WHERE pricing.surge_runs.computed_at <= clock_timestamp() - interval '60 seconds'
                  AND pricing.surge_runs.sampled_at < EXCLUDED.sampled_at
                RETURNING computed_at
                """).param("city", claim.cityId()).param("sampledAt", observation.sampledAt().atOffset(ZoneOffset.UTC))
                .param("holder", claim.holder()).param("token", claim.token()).query(OffsetDateTime.class).optional();
        if (computedAt.isEmpty()) {
            return false;
        }
        Map<String, BigDecimal> previous = new HashMap<>();
        jdbc.sql("SELECT zone_id, multiplier FROM pricing.surge_multipliers WHERE city_id = :city ORDER BY zone_id LIMIT :limit")
                .param("city", claim.cityId()).param("limit", MAX_ZONES + 1)
                .query((row, rowNumber) -> Map.entry(row.getString("zone_id"), row.getBigDecimal("multiplier")))
                .list().forEach(entry -> previous.put(entry.getKey(), entry.getValue()));
        TreeSet<String> zones = new TreeSet<>(previous.keySet());
        zones.addAll(observation.demand().keySet());
        zones.addAll(supply.keySet());
        if (zones.size() > MAX_ZONES) {
            throw new IllegalStateException("Surge computation exceeds the zone limit");
        }
        List<Value> values = new ArrayList<>();
        for (String zone : zones) {
            Demand demand = observation.demand().getOrDefault(zone, NO_DEMAND);
            ZoneCount drivers = supply.getOrDefault(zone, NO_SUPPLY);
            var result = calculator.calculate(demand.rides(), demand.quotes(), drivers.local(), drivers.neighboring(),
                    previous.getOrDefault(zone, new BigDecimal("1.00")));
            values.add(new Value(zone, result.multiplier(), result.target(), demand.rides(), demand.quotes(),
                    drivers.local(), drivers.neighboring(), result.demand(), result.supply()));
        }
        jdbc.sql("""
                WITH values AS MATERIALIZED (
                    SELECT * FROM jsonb_to_recordset(CAST(:values AS jsonb))
                        AS record(zone text, multiplier numeric, target numeric, rides bigint, quotes bigint,
                                  available bigint, neighboring bigint, demand numeric, supply numeric)
                ), updated AS (
                    INSERT INTO pricing.surge_multipliers (city_id, zone_id, multiplier, sampled_at, computed_at)
                    SELECT :city, zone, multiplier, :sampledAt, :computedAt FROM values
                    ON CONFLICT (city_id, zone_id) DO UPDATE SET multiplier = EXCLUDED.multiplier,
                        sampled_at = EXCLUDED.sampled_at, computed_at = EXCLUDED.computed_at
                    RETURNING zone_id
                )
                INSERT INTO pricing.surge_history (city_id, zone_id, sampled_at, computed_at, multiplier, target,
                    rides, quotes, available, neighboring, demand, supply, steps, owner_holder, owner_token)
                SELECT :city, values.zone, :sampledAt, :computedAt, values.multiplier, values.target,
                    values.rides, values.quotes, values.available, values.neighboring, values.demand, values.supply,
                    CAST(:steps AS jsonb), :holder, :token
                FROM values JOIN updated ON updated.zone_id = values.zone
                """).param("city", claim.cityId()).param("sampledAt", observation.sampledAt().atOffset(ZoneOffset.UTC))
                .param("computedAt", computedAt.orElseThrow()).param("holder", claim.holder()).param("token", claim.token())
                .param("values", json.writeValueAsString(values)).param("steps", json.writeValueAsString(steps)).update();
        return true;
    }

    public Optional<BigDecimal> current(String cityId, String zoneId) {
        return jdbc.sql("""
                SELECT multiplier FROM pricing.surge_multipliers
                WHERE city_id = :city AND zone_id = :zone
                  AND sampled_at <= clock_timestamp() AND sampled_at > clock_timestamp() - interval '2 minutes'
                """).param("city", cityId).param("zone", zoneId).query(BigDecimal.class).optional();
    }

    public int deleteHistory(int batch) {
        return jdbc.sql("""
                DELETE FROM pricing.surge_history WHERE (city_id, zone_id, computed_at) IN (
                    SELECT city_id, zone_id, computed_at FROM pricing.surge_history
                    WHERE computed_at < clock_timestamp() - interval '7 days' ORDER BY computed_at LIMIT :batch)
                """).param("batch", batch).update();
    }

    public record Demand(long rides, long quotes) {
    }

    public record Observation(Instant sampledAt, Map<String, Demand> demand) {
        public Observation {
            demand = Map.copyOf(demand);
        }
    }

    private record Value(String zone, BigDecimal multiplier, BigDecimal target, long rides, long quotes,
            long available, long neighboring, BigDecimal demand, BigDecimal supply) {
    }
}
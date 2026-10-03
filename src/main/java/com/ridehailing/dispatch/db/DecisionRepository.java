package com.ridehailing.dispatch.db;

import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** One decision record per search attempt, kept 30 days (FR-DS6, LLD §8.3). */
@Repository
public class DecisionRepository {

    private final JdbcClient jdbc;

    DecisionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code chosenDriverId} and {@code offerId} are null unless the outcome is {@code OFFERED}. */
    public void insert(UUID id, UUID rideId, int attempt, String strategy, String strategyVersion, int radiusM,
            String outcome, UUID chosenDriverId, UUID offerId, String detail, long durationUs) {
        jdbc.sql("""
                        INSERT INTO dispatch.decisions (id, ride_id, attempt, created_at, strategy, strategy_version,
                                                        radius_m, outcome, chosen_driver_id, offer_id, detail,
                                                        duration_us)
                        VALUES (:id, :rideId, :attempt, now(), :strategy, :strategyVersion, :radiusM, :outcome,
                                :chosenDriverId, :offerId, CAST(:detail AS jsonb), :durationUs)
                        """)
                .param("id", id)
                .param("rideId", rideId)
                .param("attempt", attempt)
                .param("strategy", strategy)
                .param("strategyVersion", strategyVersion)
                .param("radiusM", radiusM)
                .param("outcome", outcome)
                .param("chosenDriverId", chosenDriverId)
                .param("offerId", offerId)
                .param("detail", detail)
                .param("durationUs", (int) Math.min(durationUs, Integer.MAX_VALUE))
                .update();
    }

    /** Deletes up to {@code batch} decisions older than {@code age}; answers how many. */
    public int deleteOlderThan(Duration age, int batch) {
        return jdbc.sql("""
                        DELETE FROM dispatch.decisions WHERE id IN (
                            SELECT id FROM dispatch.decisions
                            WHERE created_at < now() - make_interval(secs => :ageS) LIMIT :batch)
                        """)
                .param("ageS", age.toSeconds())
                .param("batch", batch)
                .update();
    }
}

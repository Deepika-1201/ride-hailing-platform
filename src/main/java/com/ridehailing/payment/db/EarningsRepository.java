package com.ridehailing.payment.db;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Driver earnings, a read model (ADR-014, LLD §11.8): unique per source and kind. */
@Repository
public class EarningsRepository {

    private final JdbcClient jdbc;

    EarningsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Does nothing if the source already has its row: a redelivered event. */
    public void insert(NewEarning earning) {
        jdbc.sql("""
                        INSERT INTO payment.driver_earnings (id, driver_id, ride_id, kind, source_id, gross_paise,
                            commission_paise, net_paise, cash_collected_paise, currency, earned_at, earned_on)
                        VALUES (:id, :driverId, :rideId, :kind, :sourceId, :grossPaise, :commissionPaise, :netPaise,
                            :cashCollectedPaise, :currency, :earnedAt, :earnedOn)
                        ON CONFLICT (source_id, kind) DO NOTHING
                        """)
                .param("id", earning.id())
                .param("driverId", earning.driverId())
                .param("rideId", earning.rideId())
                .param("kind", earning.kind())
                .param("sourceId", earning.sourceId())
                .param("grossPaise", earning.grossPaise())
                .param("commissionPaise", earning.commissionPaise())
                .param("netPaise", earning.grossPaise() - earning.commissionPaise())
                .param("cashCollectedPaise", earning.cashCollectedPaise())
                .param("currency", earning.currency())
                .param("earnedAt", earning.earnedAt().atOffset(ZoneOffset.UTC))
                .param("earnedOn", earning.earnedOn())
                .update();
    }

    /** Sums per day and currency, for days with rows, oldest first; {@code rides} counts fares. */
    public List<DaySum> days(UUID driverId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT earned_on, currency, count(*) FILTER (WHERE kind = 'FARE') AS rides,
                               sum(gross_paise) AS gross, sum(commission_paise) AS commission, sum(net_paise) AS net,
                               sum(cash_collected_paise) AS cash_collected
                        FROM payment.driver_earnings
                        WHERE driver_id = :driverId AND earned_on BETWEEN :from AND :to
                        GROUP BY earned_on, currency
                        ORDER BY earned_on, currency
                        """)
                .param("driverId", driverId)
                .param("from", from)
                .param("to", to)
                .query((row, rowNumber) -> new DaySum(row.getObject("earned_on", LocalDate.class),
                        row.getString("currency"), row.getInt("rides"), row.getLong("gross"),
                        row.getLong("commission"), row.getLong("net"), row.getLong("cash_collected")))
                .list();
    }

    public record NewEarning(UUID id, UUID driverId, UUID rideId, String kind, UUID sourceId, long grossPaise,
            long commissionPaise, long cashCollectedPaise, String currency, Instant earnedAt, LocalDate earnedOn) {
    }

    public record DaySum(LocalDate day, String currency, int rides, long grossPaise, long commissionPaise,
            long netPaise, long cashCollectedPaise) {
    }
}

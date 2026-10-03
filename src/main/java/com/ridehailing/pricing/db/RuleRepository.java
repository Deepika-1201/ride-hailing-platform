package com.ridehailing.pricing.db;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Fare and fee rule versions: inserted, never updated (ADR-012, LLD §10.5). */
@Repository
public class RuleRepository {

    private static final String FARE_COLUMNS = """
            id, city_id, category, version, effective_from, base_paise, per_km_paise, per_min_paise, minimum_paise,
            booking_fee_paise, tax_bp, commission_bp, currency, created_at
            """;
    private static final String FEE_COLUMNS = """
            id, city_id, category, version, effective_from, cancellation_fee_paise, no_show_fee_paise,
            free_cancel_window_s, late_grace_s, pickup_wait_s, commission_bp, currency, created_at
            """;

    private final JdbcClient jdbc;

    RuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The next version of the city's fare or fee rule for the category. Holds a transaction-scoped advisory lock
     * until commit, so concurrent publishers get consecutive versions.
     */
    public int lockNextVersion(Kind kind, String cityId, String category) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", kind.table + ":" + cityId + ":" + category)
                .query()
                .singleRow();
        return jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM pricing." + kind.table
                        + " WHERE city_id = :cityId AND category = :category")
                .param("cityId", cityId)
                .param("category", category)
                .query(Integer.class)
                .single();
    }

    /** True if the time is before the start of this transaction, by the database clock. */
    public boolean inPast(Instant time) {
        return jdbc.sql("SELECT CAST(:time AS timestamptz) < now()")
                .param("time", utc(time))
                .query(Boolean.class)
                .single();
    }

    /** {@code effectiveFrom} null means now, by the database clock. */
    public FareRule insertFare(FareRule rule, UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO pricing.fare_rules (id, city_id, category, version, effective_from, base_paise,
                                                        per_km_paise, per_min_paise, minimum_paise, booking_fee_paise,
                                                        tax_bp, commission_bp, currency, created_by)
                        VALUES (:id, :cityId, :category, :version, coalesce(CAST(:effectiveFrom AS timestamptz), now()),
                                :basePaise, :perKmPaise, :perMinPaise, :minimumPaise, :bookingFeePaise, :taxBp,
                                :commissionBp, :currency, :createdBy)
                        RETURNING""" + " " + FARE_COLUMNS)
                .param("id", rule.id())
                .param("cityId", rule.cityId())
                .param("category", rule.category())
                .param("version", rule.version())
                .param("effectiveFrom", utc(rule.effectiveFrom()))
                .param("basePaise", rule.basePaise())
                .param("perKmPaise", rule.perKmPaise())
                .param("perMinPaise", rule.perMinPaise())
                .param("minimumPaise", rule.minimumPaise())
                .param("bookingFeePaise", rule.bookingFeePaise())
                .param("taxBp", rule.taxBp())
                .param("commissionBp", rule.commissionBp())
                .param("currency", rule.currency())
                .param("createdBy", createdBy)
                .query(FareRule.class)
                .single();
    }

    public FeeRule insertFee(FeeRule rule, UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO pricing.fee_rules (id, city_id, category, version, effective_from,
                                                       cancellation_fee_paise, no_show_fee_paise, free_cancel_window_s,
                                                       late_grace_s, pickup_wait_s, commission_bp, currency, created_by)
                        VALUES (:id, :cityId, :category, :version, coalesce(CAST(:effectiveFrom AS timestamptz), now()),
                                :cancellationFeePaise, :noShowFeePaise, :freeCancelWindowS, :lateGraceS, :pickupWaitS,
                                :commissionBp, :currency, :createdBy)
                        RETURNING""" + " " + FEE_COLUMNS)
                .param("id", rule.id())
                .param("cityId", rule.cityId())
                .param("category", rule.category())
                .param("version", rule.version())
                .param("effectiveFrom", utc(rule.effectiveFrom()))
                .param("cancellationFeePaise", rule.cancellationFeePaise())
                .param("noShowFeePaise", rule.noShowFeePaise())
                .param("freeCancelWindowS", rule.freeCancelWindowS())
                .param("lateGraceS", rule.lateGraceS())
                .param("pickupWaitS", rule.pickupWaitS())
                .param("commissionBp", rule.commissionBp())
                .param("currency", rule.currency())
                .param("createdBy", createdBy)
                .query(FeeRule.class)
                .single();
    }

    /** Newest version first, per category; {@code category} null means all of the city's. */
    public List<FareRule> fareRules(String cityId, String category) {
        return jdbc.sql("SELECT " + FARE_COLUMNS + """
                         FROM pricing.fare_rules
                        WHERE city_id = :cityId AND (CAST(:category AS text) IS NULL OR category = :category)
                        ORDER BY category, version DESC
                        """)
                .param("cityId", cityId)
                .param("category", category)
                .query(FareRule.class)
                .list();
    }

    public List<FeeRule> feeRules(String cityId, String category) {
        return jdbc.sql("SELECT " + FEE_COLUMNS + """
                         FROM pricing.fee_rules
                        WHERE city_id = :cityId AND (CAST(:category AS text) IS NULL OR category = :category)
                        ORDER BY category, version DESC
                        """)
                .param("cityId", cityId)
                .param("category", category)
                .query(FeeRule.class)
                .list();
    }

    // The PostgreSQL driver takes OffsetDateTime, not Instant.
    private static OffsetDateTime utc(Instant time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }

    /** The version with the latest start not after now, by the database clock; the higher version on a tie. */
    public Optional<FareRule> fareInEffect(String cityId, String category) {
        return jdbc.sql("SELECT " + FARE_COLUMNS + """
                         FROM pricing.fare_rules
                        WHERE city_id = :cityId AND category = :category AND effective_from <= now()
                        ORDER BY effective_from DESC, version DESC
                        LIMIT 1
                        """)
                .param("cityId", cityId)
                .param("category", category)
                .query(FareRule.class)
                .optional();
    }

    public Optional<FeeRule> feeInEffect(String cityId, String category) {
        return jdbc.sql("SELECT " + FEE_COLUMNS + """
                         FROM pricing.fee_rules
                        WHERE city_id = :cityId AND category = :category AND effective_from <= now()
                        ORDER BY effective_from DESC, version DESC
                        LIMIT 1
                        """)
                .param("cityId", cityId)
                .param("category", category)
                .query(FeeRule.class)
                .optional();
    }

    public enum Kind {
        FARE("fare_rules"),
        FEE("fee_rules");

        private final String table;

        Kind(String table) {
            this.table = table;
        }
    }

    /** {@code createdAt} is null until inserted; {@code effectiveFrom} may be null before, meaning now. */
    public record FareRule(UUID id, String cityId, String category, int version, Instant effectiveFrom,
            long basePaise, long perKmPaise, long perMinPaise, long minimumPaise, long bookingFeePaise, int taxBp,
            int commissionBp, String currency, Instant createdAt) {
    }

    public record FeeRule(UUID id, String cityId, String category, int version, Instant effectiveFrom,
            long cancellationFeePaise, long noShowFeePaise, int freeCancelWindowS, int lateGraceS, int pickupWaitS,
            int commissionBp, String currency, Instant createdAt) {
    }
}

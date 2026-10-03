package com.ridehailing.pricing.app;

import com.ridehailing.audit.AuditEntry;
import com.ridehailing.audit.AuditLog;
import com.ridehailing.geography.GeographyApi;
import com.ridehailing.geography.GeographyApi.CityView;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Transactions;
import com.ridehailing.pricing.db.RuleRepository;
import com.ridehailing.pricing.db.RuleRepository.FareRule;
import com.ridehailing.pricing.db.RuleRepository.FeeRule;
import com.ridehailing.pricing.db.RuleRepository.Kind;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Publishes fare and fee rule versions. A version is never edited and never takes effect in the past, so the price
 * in effect at any moment never changes after the fact (ADR-012, LLD §10.5).
 */
@Service
public class RulePublishing {

    private final RuleRepository rules;
    private final GeographyApi geography;
    private final AuditLog auditLog;
    private final Transactions transactions;

    RulePublishing(RuleRepository rules, GeographyApi geography, AuditLog auditLog, Transactions transactions) {
        this.rules = rules;
        this.geography = geography;
        this.auditLog = auditLog;
        this.transactions = transactions;
    }

    public List<FareRule> fareRules(String cityId, String category) {
        return rules.fareRules(cityId, category);
    }

    public List<FeeRule> feeRules(String cityId, String category) {
        return rules.feeRules(cityId, category);
    }

    /** {@code rule.id}, {@code version} and {@code createdAt} are ignored; a null {@code effectiveFrom} means now. */
    public FareRule publish(Caller admin, FareRule rule) {
        checkTarget(rule.cityId(), rule.category(), rule.currency());
        return transactions.execute(() -> {
            checkNotInPast(rule.effectiveFrom());
            int version = rules.lockNextVersion(Kind.FARE, rule.cityId(), rule.category());
            FareRule published = rules.insertFare(new FareRule(Ids.newId(), rule.cityId(), rule.category(), version,
                    rule.effectiveFrom(), rule.basePaise(), rule.perKmPaise(), rule.perMinPaise(),
                    rule.minimumPaise(), rule.bookingFeePaise(), rule.taxBp(), rule.commissionBp(), rule.currency(),
                    null), admin.userId());
            audit(admin, "fare_rule.publish", "fare_rule", published.id().toString(), Map.ofEntries(
                    Map.entry("city_id", published.cityId()), Map.entry("category", published.category()),
                    Map.entry("version", published.version()),
                    Map.entry("effective_from", published.effectiveFrom().toString()),
                    Map.entry("base_paise", published.basePaise()), Map.entry("per_km_paise", published.perKmPaise()),
                    Map.entry("per_min_paise", published.perMinPaise()),
                    Map.entry("minimum_paise", published.minimumPaise()),
                    Map.entry("booking_fee_paise", published.bookingFeePaise()),
                    Map.entry("tax_bp", published.taxBp()), Map.entry("commission_bp", published.commissionBp())));
            return published;
        });
    }

    public FeeRule publish(Caller admin, FeeRule rule) {
        checkTarget(rule.cityId(), rule.category(), rule.currency());
        return transactions.execute(() -> {
            checkNotInPast(rule.effectiveFrom());
            int version = rules.lockNextVersion(Kind.FEE, rule.cityId(), rule.category());
            FeeRule published = rules.insertFee(new FeeRule(Ids.newId(), rule.cityId(), rule.category(), version,
                    rule.effectiveFrom(), rule.cancellationFeePaise(), rule.noShowFeePaise(),
                    rule.freeCancelWindowS(), rule.lateGraceS(), rule.pickupWaitS(), rule.commissionBp(),
                    rule.currency(), null), admin.userId());
            audit(admin, "fee_rule.publish", "fee_rule", published.id().toString(), Map.ofEntries(
                    Map.entry("city_id", published.cityId()), Map.entry("category", published.category()),
                    Map.entry("version", published.version()),
                    Map.entry("effective_from", published.effectiveFrom().toString()),
                    Map.entry("cancellation_fee_paise", published.cancellationFeePaise()),
                    Map.entry("no_show_fee_paise", published.noShowFeePaise()),
                    Map.entry("free_cancel_window_s", published.freeCancelWindowS()),
                    Map.entry("late_grace_s", published.lateGraceS()),
                    Map.entry("pickup_wait_s", published.pickupWaitS()),
                    Map.entry("commission_bp", published.commissionBp())));
            return published;
        });
    }

    private void checkTarget(String cityId, String category, String currency) {
        CityView city = geography.city(cityId)
                .orElseThrow(() -> ApiException.invalid("city_id", "is not a known city"));
        if (!city.currency().equals(currency)) {
            throw ApiException.invalid("currency", "must be the city's currency, " + city.currency());
        }
        if (!geography.offers(cityId, category)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "CATEGORY_NOT_AVAILABLE",
                    "The city doesn't offer this category.");
        }
    }

    private void checkNotInPast(Instant effectiveFrom) {
        if (effectiveFrom != null && rules.inPast(effectiveFrom)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "RULE_EFFECTIVE_IN_PAST",
                    "A rule can't take effect in the past; leave effective_from out to mean now.");
        }
    }

    private void audit(Caller admin, String action, String entityType, String entityId, Map<String, Object> after) {
        auditLog.record(new AuditEntry(admin.as(UserRole.ADMIN), action, entityType, entityId, null, null, after));
    }
}

package com.ridehailing.pricing.app;

import com.ridehailing.audit.AuditEntry;
import com.ridehailing.audit.AuditLog;
import com.ridehailing.geography.GeographyApi;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Transactions;
import com.ridehailing.pricing.db.SurgeRuleRepository;
import com.ridehailing.pricing.db.SurgeRuleRepository.SurgeRule;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Surge rules, changed in place with a version check (LLD §10.3, §10.5). */
@Service
public class SurgeRuleAdministration {

    private final SurgeRuleRepository rules;
    private final SurgeRuleCache cache;
    private final GeographyApi geography;
    private final AuditLog auditLog;
    private final Transactions transactions;

    SurgeRuleAdministration(SurgeRuleRepository rules, SurgeRuleCache cache, GeographyApi geography,
            AuditLog auditLog, Transactions transactions) {
        this.rules = rules;
        this.cache = cache;
        this.geography = geography;
        this.auditLog = auditLog;
        this.transactions = transactions;
    }

    public List<SurgeRule> list(String cityId) {
        return rules.list(cityId);
    }

    /** {@code rule.id}, {@code active} and {@code version} are ignored. */
    public SurgeRule create(Caller admin, SurgeRule rule) {
        if (geography.city(rule.cityId()).isEmpty()) {
            throw ApiException.invalid("city_id", "is not a known city");
        }
        if (!geography.isZone(rule.cityId(), rule.zoneId())) {
            throw ApiException.invalid("zone_id",
                    "must be an H3 resolution-7 cell or area:<code> of an active special area in the city");
        }
        if (rule.daysOfWeek().stream().distinct().count() != rule.daysOfWeek().size()) {
            throw ApiException.invalid("days_of_week", "must not repeat a day");
        }
        if (rule.startLocal().equals(rule.endLocal())) {
            throw ApiException.invalid("end_local", "must differ from start_local");
        }
        List<Integer> days = rule.daysOfWeek().stream().sorted().toList();
        SurgeRule result = transactions.execute(() -> {
            SurgeRule created = rules.insert(new SurgeRule(Ids.newId(), rule.cityId(), rule.zoneId(), days,
                    rule.startLocal(), rule.endLocal(), rule.multiplier(), true, 0), admin.userId());
            audit(admin, "surge_rule.create", created.id(), Map.of("city_id", created.cityId(),
                    "zone_id", created.zoneId(), "days_of_week", days, "start_local", created.startLocal().toString(),
                    "end_local", created.endLocal().toString(), "multiplier", created.multiplier()));
            return created;
        });
        cache.invalidate(result.cityId());
        return result;
    }

    /** {@code multiplier} and {@code active} are optional; {@code version} is the one the admin read. */
    public SurgeRule update(Caller admin, UUID id, BigDecimal multiplier, Boolean active, int version) {
        SurgeRule result = transactions.execute(() -> {
            SurgeRule current = rules.find(id).orElseThrow(ApiException::notFound);
            SurgeRule updated = rules.update(id, version, multiplier, active)
                    .orElseThrow(() -> ApiException.versionConflict(current.version()));
            Map<String, Object> changed = new HashMap<>();
            if (multiplier != null) {
                changed.put("multiplier", updated.multiplier());
            }
            if (active != null) {
                changed.put("active", active);
            }
            audit(admin, "surge_rule.update", id, changed);
            return updated;
        });
        cache.invalidate(result.cityId());
        return result;
    }

    private void audit(Caller admin, String action, UUID ruleId, Map<String, Object> after) {
        auditLog.record(new AuditEntry(admin.as(UserRole.ADMIN), action, "surge_rule", ruleId.toString(), null,
                null, after));
    }
}

package com.ridehailing.geography.app;

import com.ridehailing.audit.AuditEntry;
import com.ridehailing.audit.AuditLog;
import com.ridehailing.geography.db.CategoryRepository;
import com.ridehailing.geography.db.CategoryRepository.Category;
import com.ridehailing.geography.db.CategoryRepository.Settings;
import com.ridehailing.geography.db.CityRepository;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Transactions;
import com.ridehailing.shared.UserRole;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Which categories each city offers, and its dispatch settings for them (LLD §4.4, §13.3). */
@Service
public class CategoryAdministration {

    private final CityRepository cities;
    private final CategoryRepository categories;
    private final AuditLog auditLog;
    private final Transactions transactions;

    CategoryAdministration(CityRepository cities, CategoryRepository categories, AuditLog auditLog,
            Transactions transactions) {
        this.cities = cities;
        this.categories = categories;
        this.auditLog = auditLog;
        this.transactions = transactions;
    }

    public List<Category> categories() {
        return categories.categories();
    }

    public Settings get(String cityId, String category) {
        return categories.settings(cityId, category).orElseThrow(ApiException::notFound);
    }

    /**
     * Creates the settings at version 0 if the city has none for the category; otherwise replaces them, but only at
     * the version the admin read.
     */
    public Settings put(Caller admin, Settings wanted, Integer expectedVersion) {
        if (wanted.radiusStartM() > wanted.radiusMaxM()) {
            throw ApiException.invalid("radius_start_m", "must not be greater than radius_max_m");
        }
        return transactions.execute(() -> {
            if (cities.find(wanted.cityId()).isEmpty() || !categories.categoryExists(wanted.category())) {
                throw ApiException.notFound();
            }
            Settings result;
            if (categories.insert(wanted)) {
                result = categories.settings(wanted.cityId(), wanted.category()).orElseThrow();
            } else {
                Settings current = categories.settings(wanted.cityId(), wanted.category()).orElseThrow();
                if (expectedVersion == null) {
                    throw ApiException.versionConflict(current.version());
                }
                result = categories.update(wanted, expectedVersion)
                        .orElseThrow(() -> ApiException.versionConflict(current.version()));
            }
            auditLog.record(new AuditEntry(admin.as(UserRole.ADMIN), "city_category.put", "city_category",
                    wanted.cityId() + ":" + wanted.category(), null, null, Map.of(
                            "active", result.active(), "offer_ttl_s", result.offerTtlS(),
                            "search_timeout_s", result.searchTimeoutS(), "radius_start_m", result.radiusStartM(),
                            "radius_step_m", result.radiusStepM(), "radius_max_m", result.radiusMaxM(),
                            "ranker", result.ranker())));
            return result;
        });
    }
}

package com.ridehailing.geography.app;

import com.ridehailing.audit.AuditEntry;
import com.ridehailing.audit.AuditLog;
import com.ridehailing.geography.db.CityRepository;
import com.ridehailing.geography.db.CityRepository.CityRow;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Transactions;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Cities and their service areas, managed by admins (LLD §13.3). */
@Service
public class CityAdministration {

    /** V1 serves India only (ADR-014). */
    private static final Set<String> CURRENCIES = Set.of("INR");

    private final CityRepository cities;
    private final GeoJsonShapes shapes;
    private final AuditLog auditLog;
    private final Transactions transactions;

    CityAdministration(CityRepository cities, GeoJsonShapes shapes, AuditLog auditLog, Transactions transactions) {
        this.cities = cities;
        this.shapes = shapes;
        this.auditLog = auditLog;
        this.transactions = transactions;
    }

    public List<CityRow> list() {
        return cities.list();
    }

    public CityRow get(String cityId) {
        return cities.find(cityId).orElseThrow(ApiException::notFound);
    }

    public CityRow create(Caller admin, NewCity city) {
        if (!ZoneId.getAvailableZoneIds().contains(city.timeZone())) {
            throw ApiException.invalid("time_zone", "must be an IANA time zone, such as Asia/Kolkata");
        }
        if (!CURRENCIES.contains(city.currency())) {
            throw ApiException.invalid("currency", "must be one of " + CURRENCIES);
        }
        String bounds = shapes.require(city.bounds(), "bounds", "Polygon");
        return transactions.execute(() -> {
            if (!cities.insert(city.id(), city.name(), city.timeZone(), city.currency(), bounds)) {
                throw ApiException.alreadyExists("A city with this ID exists already.");
            }
            audit(admin, "city.create", city.id(), Map.of("name", city.name(), "time_zone", city.timeZone()));
            return cities.find(city.id()).orElseThrow();
        });
    }

    public CityRow update(Caller admin, String cityId, CityChange change) {
        return transactions.execute(() -> {
            CityRow current = cities.find(cityId).orElseThrow(ApiException::notFound);
            CityRow updated = cities.update(cityId, change.version(), change.name(), change.active())
                    .orElseThrow(() -> ApiException.versionConflict(current.version()));
            Map<String, Object> changed = new HashMap<>();
            if (change.name() != null) {
                changed.put("name", change.name());
            }
            if (change.active() != null) {
                changed.put("active", change.active());
            }
            audit(admin, "city.update", cityId, changed);
            return updated;
        });
    }

    public void replaceServiceArea(Caller admin, String cityId, JsonNode area) {
        String geoJson = shapes.require(area, "area", "MultiPolygon");
        transactions.run(() -> {
            cities.lock(cityId).orElseThrow(ApiException::notFound);
            cities.replaceServiceArea(cityId, Ids.newId(), geoJson);
            audit(admin, "city.service_area.replace", cityId, Map.of());
        });
    }

    private void audit(Caller admin, String action, String cityId, Map<String, Object> after) {
        auditLog.record(new AuditEntry(admin.as(UserRole.ADMIN), action, "city", cityId, null, null, after));
    }

    public record NewCity(String id, String name, String timeZone, String currency, JsonNode bounds) {
    }

    /** {@code name} and {@code active} are optional; {@code version} is the one the admin read. */
    public record CityChange(String name, Boolean active, int version) {
    }
}

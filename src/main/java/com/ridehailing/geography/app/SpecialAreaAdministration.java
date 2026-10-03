package com.ridehailing.geography.app;

import com.ridehailing.audit.AuditEntry;
import com.ridehailing.audit.AuditLog;
import com.ridehailing.geography.db.CityRepository;
import com.ridehailing.geography.db.SpecialAreaRepository;
import com.ridehailing.geography.db.SpecialAreaRepository.SpecialAreaRow;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Transactions;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Airports, stations and other special areas; each is a pricing zone of its own (LLD §10.1, §13.3). */
@Service
public class SpecialAreaAdministration {

    private final CityRepository cities;
    private final SpecialAreaRepository areas;
    private final GeoJsonShapes shapes;
    private final AuditLog auditLog;
    private final Transactions transactions;

    SpecialAreaAdministration(CityRepository cities, SpecialAreaRepository areas, GeoJsonShapes shapes,
            AuditLog auditLog, Transactions transactions) {
        this.cities = cities;
        this.areas = areas;
        this.shapes = shapes;
        this.auditLog = auditLog;
        this.transactions = transactions;
    }

    public List<SpecialAreaRow> list(String cityId) {
        cities.find(cityId).orElseThrow(ApiException::notFound);
        return areas.list(cityId);
    }

    public SpecialAreaRow create(Caller admin, String cityId, NewSpecialArea area) {
        String geoJson = shapes.require(area.area(), "area", "MultiPolygon");
        return transactions.execute(() -> {
            cities.find(cityId).orElseThrow(ApiException::notFound);
            UUID id = Ids.newId();
            if (!areas.insert(id, cityId, area.code(), area.name(), area.kind(), geoJson, area.priority())) {
                throw ApiException.alreadyExists("A special area with this code exists already.");
            }
            audit(admin, "special_area.create", id, Map.of("city_id", cityId, "code", area.code()));
            return areas.get(id);
        });
    }

    public void deactivate(Caller admin, String cityId, UUID areaId) {
        transactions.run(() -> {
            if (!areas.deactivate(cityId, areaId)) {
                throw ApiException.notFound();
            }
            audit(admin, "special_area.deactivate", areaId, Map.of("active", false));
        });
    }

    private void audit(Caller admin, String action, UUID areaId, Map<String, Object> after) {
        auditLog.record(new AuditEntry(admin.as(UserRole.ADMIN), action, "special_area", areaId.toString(), null,
                null, after));
    }

    public record NewSpecialArea(String code, String name, String kind, JsonNode area, int priority) {
    }
}

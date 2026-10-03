package com.ridehailing.geography.app;

import com.ridehailing.geography.GeographyApi;
import com.ridehailing.geography.db.CategoryRepository;
import com.ridehailing.geography.db.CityRepository;
import com.ridehailing.geography.db.SpatialQueries;
import com.ridehailing.geography.db.SpecialAreaRepository;
import com.ridehailing.shared.GeoPoint;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
class GeographyService implements GeographyApi {

    static final String AREA_PREFIX = "area:";

    private final CityRepository cities;
    private final CategoryRepository categories;
    private final SpecialAreaRepository specialAreas;
    private final SpatialQueries spatial;
    private final H3Zones zones;

    GeographyService(CityRepository cities, CategoryRepository categories, SpecialAreaRepository specialAreas,
            SpatialQueries spatial, H3Zones zones) {
        this.cities = cities;
        this.categories = categories;
        this.specialAreas = specialAreas;
        this.spatial = spatial;
        this.zones = zones;
    }

    @Override
    public Optional<CityView> city(String cityId) {
        return cities.find(cityId).map(city -> new CityView(city.id(), city.name(), ZoneId.of(city.timeZone()),
                city.currency(), city.active()));
    }

    @Override
    public boolean offers(String cityId, String category) {
        return categories.offers(cityId, category);
    }

    @Override
    public Optional<Location> locate(GeoPoint point) {
        return spatial.containing(point).map(found -> new Location(found.cityId(),
                found.specialArea() != null ? AREA_PREFIX + found.specialArea() : zones.cellOf(point)));
    }

    @Override
    public boolean isZone(String cityId, String zoneId) {
        if (zoneId.startsWith(AREA_PREFIX)) {
            return specialAreas.activeCodeIn(cityId, zoneId.substring(AREA_PREFIX.length()));
        }
        return zones.isCell(zoneId);
    }
}

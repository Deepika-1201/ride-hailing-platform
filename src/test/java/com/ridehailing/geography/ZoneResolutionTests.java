package com.ridehailing.geography;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.geography.GeographyApi.Location;
import com.ridehailing.geography.app.CityAdministration;
import com.ridehailing.geography.app.SpecialAreaAdministration;
import com.ridehailing.platform.Caller;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.uber.h3core.H3Core;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** LLD §10.1: the zone is the special area containing the point, by priority, else the point's H3 resolution-7 cell. */
class ZoneResolutionTests extends IntegrationTest {

    @Autowired
    private GeographyApi geography;

    @Autowired
    private TestCities cities;

    @Autowired
    private SpecialAreaAdministration specialAreas;

    @Autowired
    private CityAdministration cityAdministration;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void aSpecialAreaTakesPrecedenceOverTheH3Cell() {
        TestCity city = cities.create();
        String code = TestCities.newCode();
        cities.addSpecialArea(city, code, 0, 0.1, 0.1, 0.1);

        assertThat(geography.locate(city.at(0.15, 0.15))).contains(new Location(city.id(), "area:" + code));
        GeoPoint outside = city.at(0.3, 0.3);
        assertThat(geography.locate(outside)).contains(new Location(city.id(), cellOf(outside)));
    }

    @Test
    void whereAreasOverlapTheHighestPriorityWinsThenTheLowestCode() {
        TestCity city = cities.create();
        // The higher priority has the later code, so ordering by code alone would pick the other.
        String low = "T-AAAA" + TestCities.newCode().substring(2);
        String high = "T-ZZZZ" + TestCities.newCode().substring(2);
        cities.addSpecialArea(city, low, 1, 0.1, 0.1, 0.2);
        cities.addSpecialArea(city, high, 5, 0.15, 0.15, 0.2);

        assertThat(geography.locate(city.at(0.2, 0.2)).map(Location::zoneId)).contains("area:" + high);
        assertThat(geography.locate(city.at(0.12, 0.12)).map(Location::zoneId)).contains("area:" + low);

        String first = "T-AAAA" + TestCities.newCode().substring(2);
        String second = "T-ZZZZ" + TestCities.newCode().substring(2);
        TestCity tied = cities.create();
        cities.addSpecialArea(tied, second, 3, 0.1, 0.1, 0.2);
        cities.addSpecialArea(tied, first, 3, 0.1, 0.1, 0.2);
        assertThat(geography.locate(tied.at(0.2, 0.2)).map(Location::zoneId)).contains("area:" + first);
    }

    @Test
    void aDeactivatedAreaNoLongerCounts() {
        TestCity city = cities.create();
        String code = TestCities.newCode();
        cities.addSpecialArea(city, code, 0, 0.1, 0.1, 0.1);
        GeoPoint inside = city.at(0.15, 0.15);

        specialAreas.deactivate(new Caller(Ids.newId(), Set.of(UserRole.ADMIN)), city.id(), areaId(code));

        assertThat(geography.locate(inside).map(Location::zoneId)).contains(cellOf(inside));
        assertThat(geography.isZone(city.id(), "area:" + code)).isFalse();
    }

    @Test
    void aPointOutsideEveryServiceAreaHasNoZone() {
        TestCity city = cities.create();

        assertThat(geography.locate(city.at(-0.05, 0.2))).isEmpty();
        assertThat(geography.locate(city.at(TestCities.SIZE + 0.05, 0.2))).isEmpty();
    }

    @Test
    void aReplacedServiceAreaNoLongerCounts() {
        TestCity city = cities.create();
        GeoPoint dropped = city.at(0.3, 0.3);
        assertThat(geography.locate(dropped)).isPresent();

        cityAdministration.replaceServiceArea(new Caller(Ids.newId(), Set.of(UserRole.ADMIN)), city.id(),
                TestCities.multiPolygon(city.lon(), city.lat(), 0.2));

        assertThat(geography.locate(dropped)).isEmpty();
        assertThat(geography.locate(city.at(0.1, 0.1)).map(Location::cityId)).contains(city.id());
    }

    @Test
    void zonesAreResolutionSevenCellsOrActiveAreasOfTheCity() throws IOException {
        TestCity city = cities.create();
        TestCity other = cities.create();
        String code = TestCities.newCode();
        cities.addSpecialArea(city, code, 0, 0.1, 0.1, 0.1);
        H3Core h3 = H3Core.newInstance();
        GeoPoint point = city.at(0.2, 0.2);

        assertThat(geography.isZone(city.id(), h3.latLngToCellAddress(point.lat(), point.lon(), 7))).isTrue();
        assertThat(geography.isZone(city.id(), h3.latLngToCellAddress(point.lat(), point.lon(), 8))).isFalse();
        assertThat(geography.isZone(city.id(), "not-a-cell")).isFalse();
        assertThat(geography.isZone(city.id(), "area:" + code)).isTrue();
        assertThat(geography.isZone(other.id(), "area:" + code)).isFalse();
        assertThat(geography.isZone(city.id(), "area:NOWHERE")).isFalse();
    }

    private UUID areaId(String code) {
        return jdbc.sql("SELECT id FROM geography.special_areas WHERE code = :code").param("code", code)
                .query(UUID.class).single();
    }

    private static String cellOf(GeoPoint point) {
        try {
            return H3Core.newInstance().latLngToCellAddress(point.lat(), point.lon(), 7);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}

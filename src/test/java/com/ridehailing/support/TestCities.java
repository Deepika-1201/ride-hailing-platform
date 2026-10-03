package com.ridehailing.support;

import com.ridehailing.geography.app.CategoryAdministration;
import com.ridehailing.geography.app.CityAdministration;
import com.ridehailing.geography.app.CityAdministration.NewCity;
import com.ridehailing.geography.app.SpecialAreaAdministration;
import com.ridehailing.geography.app.SpecialAreaAdministration.NewSpecialArea;
import com.ridehailing.geography.db.CategoryRepository.Settings;
import com.ridehailing.platform.Caller;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestComponent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cities for tests, set up through geography's services. Each has a service area of its own, away from the seeded
 * Bengaluru and from every other test city, so tests sharing the database can't see each other's zones.
 */
@TestComponent
public class TestCities {

    /** Service areas are squares of this many degrees, on a grid in the South Atlantic. */
    public static final double SIZE = 0.4;

    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final Caller ADMIN = new Caller(Ids.newId(), Set.of(UserRole.ADMIN));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CityAdministration cities;
    private final SpecialAreaAdministration specialAreas;
    private final CategoryAdministration categories;

    TestCities(CityAdministration cities, SpecialAreaAdministration specialAreas, CategoryAdministration categories) {
        this.cities = cities;
        this.specialAreas = specialAreas;
        this.categories = categories;
    }

    /** A city with its own service area, offering the categories with default settings. */
    public TestCity create(String... offered) {
        int n = NEXT.getAndIncrement();
        double lon = -40 + (n % 60) * 0.5;
        double lat = -50 + (n / 60) * 0.5;
        String id = newId();
        cities.create(ADMIN, new NewCity(id, "Test " + id, "Asia/Kolkata", "INR", polygon(lon, lat, SIZE)));
        cities.replaceServiceArea(ADMIN, id, multiPolygon(lon, lat, SIZE));
        for (String category : offered) {
            categories.put(ADMIN, new Settings(id, category, true, 15, 180, 2000, 1000, 6000, "nearest", 0), null);
        }
        return new TestCity(id, lon, lat);
    }

    /** A square special area with its south-west corner at the offset from the city's. */
    public void addSpecialArea(TestCity city, String code, int priority, double dLon, double dLat, double size) {
        specialAreas.create(ADMIN, city.id(), new NewSpecialArea(code, "Area " + code, "OTHER",
                multiPolygon(city.lon() + dLon, city.lat() + dLat, size), priority));
    }

    /** A city ID no other test uses. */
    public static String newId() {
        return "t" + randomLetters(7, 'a');
    }

    /** A special-area code no other test uses. */
    public static String newCode() {
        return "T-" + randomLetters(8, 'A');
    }

    public static JsonNode polygon(double lon, double lat, double size) {
        return JSON.readTree("{\"type\": \"Polygon\", \"coordinates\": [" + ring(lon, lat, size) + "]}");
    }

    public static JsonNode multiPolygon(double lon, double lat, double size) {
        return JSON.readTree("{\"type\": \"MultiPolygon\", \"coordinates\": [[" + ring(lon, lat, size) + "]]}");
    }

    private static String ring(double lon, double lat, double size) {
        return "[[%s, %s], [%s, %s], [%s, %s], [%s, %s], [%s, %s]]".formatted(lon, lat, lon + size, lat, lon + size,
                lat + size, lon, lat + size, lon, lat);
    }

    private static String randomLetters(int count, char first) {
        StringBuilder letters = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            letters.append((char) (first + ThreadLocalRandom.current().nextInt(26)));
        }
        return letters.toString();
    }

    /** {@code lon} and {@code lat} are the south-west corner of the service area. */
    public record TestCity(String id, double lon, double lat) {

        public GeoPoint at(double dLon, double dLat) {
            return new GeoPoint(lat + dLat, lon + dLon);
        }
    }
}

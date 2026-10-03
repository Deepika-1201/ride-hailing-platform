package com.ridehailing;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.geography.GeographyApi;
import com.ridehailing.geography.GeographyApi.Location;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.Candidate;
import com.ridehailing.platform.AccessTokens;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.IntegrationTest;
import com.uber.h3core.H3Core;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** LLD §4.9: the local profile's Bengaluru, answering every phase-4 admin and profile read, and pricing quotes. */
@TestPropertySource(properties = "ride.seed.enabled=true")
class SeededBengaluruTests extends IntegrationTest {

    private static final UUID ADMIN = UUID.fromString("0199a3f0-0000-7000-8000-000000000002");
    private static final UUID FIRST_DRIVER = UUID.fromString("0199a3f0-0001-7000-8000-000000000001");
    private static final UUID THIRD_DRIVER = UUID.fromString("0199a3f0-0001-7000-8000-000000000003");
    private static final UUID FIRST_RIDER = UUID.fromString("0199a3f0-0002-7000-8000-000000000001");
    private static final UUID FIRST_RIDERS_CASH = UUID.fromString("0199a3f0-0004-7000-8000-000000000001");

    @Autowired
    private GeographyApi geography;

    @Autowired
    private AccessTokens accessTokens;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private LiveIndex index;

    @Test
    void bengaluruOffersEveryCategoryAndResolvesItsZones() {
        assertThat(geography.city("blr")).hasValueSatisfying(city -> {
            assertThat(city.timeZone().getId()).isEqualTo("Asia/Kolkata");
            assertThat(city.currency()).isEqualTo("INR");
        });
        assertThat(List.of("AUTO", "MINI", "SEDAN", "XL")).allMatch(category -> geography.offers("blr", category));
        assertThat(geography.locate(new GeoPoint(13.1986, 77.7066))).contains(new Location("blr", "area:BLR-AIRPORT"));
        assertThat(geography.locate(new GeoPoint(12.9784, 77.5694)).map(Location::zoneId))
                .contains("area:BLR-KSR-STATION");
        assertThat(geography.locate(new GeoPoint(12.9756, 77.6066)).map(Location::zoneId)).contains("8761892e9ffffff");
        assertThat(jdbc.sql("SELECT zone_id FROM pricing.surge_rules WHERE city_id = 'blr'").query(String.class).list())
                .hasSize(4)
                .allMatch(zone -> geography.isZone("blr", zone));
    }

    @Test
    void twoThousandVerifiedDriversHaveAVehicleAndFiveHundredRidersPayCashByDefault() {
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM driver.drivers d JOIN driver.vehicles v ON v.driver_id = d.id
                        WHERE d.city_id = 'blr' AND d.verification = 'VERIFIED' AND v.active
                        """).query(Long.class).single()).isEqualTo(2000);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM rider.riders r JOIN rider.payment_methods m
                          ON m.id = r.default_payment_method_id AND m.type = 'CASH'
                        WHERE r.user_id::text LIKE '0199a3f0-0002-%'
                        """).query(Long.class).single()).isEqualTo(500);
    }

    @Test
    void theSeededAdminReadsEveryReferenceList() {
        String admin = bearer(ADMIN, UserRole.ADMIN);

        assertAnswered("GET", "/v1/admin/cities", call("GET", admin, "/v1/admin/cities", null), 200);
        assertAnswered("GET", "/v1/admin/cities/{city_id}", call("GET", admin, "/v1/admin/cities/blr", null), 200);
        JsonNode areas = assertAnswered("GET", "/v1/admin/cities/{city_id}/special-areas",
                call("GET", admin, "/v1/admin/cities/blr/special-areas", null), 200);
        assertThat(areas.get("items")).hasSize(2);
        assertAnswered("GET", "/v1/admin/categories", call("GET", admin, "/v1/admin/categories", null), 200);
        assertAnswered("GET", "/v1/admin/cities/{city_id}/categories/{category}",
                call("GET", admin, "/v1/admin/cities/blr/categories/MINI", null), 200);
        JsonNode fares = assertAnswered("GET", "/v1/admin/fare-rules",
                call("GET", admin, "/v1/admin/fare-rules?city_id=blr", null), 200);
        assertThat(fares.get("items")).hasSize(4);
        assertAnswered("GET", "/v1/admin/fee-rules", call("GET", admin, "/v1/admin/fee-rules?city_id=blr", null), 200);
        assertAnswered("GET", "/v1/admin/surge-rules", call("GET", admin, "/v1/admin/surge-rules?city_id=blr", null),
                200);
        JsonNode drivers = assertAnswered("GET", "/v1/admin/drivers",
                call("GET", admin, "/v1/admin/drivers?city_id=blr&verification=VERIFIED&limit=5", null), 200);
        assertThat(drivers.get("items")).hasSize(5);
        // Seeded drivers share one created_at, so the second page depends on the ID breaking the tie.
        JsonNode next = assertAnswered("GET", "/v1/admin/drivers", call("GET", admin,
                "/v1/admin/drivers?city_id=blr&verification=VERIFIED&limit=5&cursor="
                        + drivers.get("next_cursor").asString(), null), 200);
        List<String> firstIds = drivers.get("items").valueStream().map(item -> item.get("id").asString()).toList();
        List<String> nextIds = next.get("items").valueStream().map(item -> item.get("id").asString()).toList();
        assertThat(nextIds).hasSize(5).doesNotContainAnyElementsOf(firstIds);
        assertThat(nextIds.getFirst()).isLessThan(firstIds.getLast());
        assertAnswered("GET", "/v1/admin/drivers/{driver_id}",
                call("GET", admin, "/v1/admin/drivers/" + FIRST_DRIVER, null), 200);
    }

    @Test
    void theSeededDriverAndRiderReadTheirOwnProfiles() {
        JsonNode driver = assertAnswered("GET", "/v1/drivers/me",
                call("GET", bearer(FIRST_DRIVER, UserRole.DRIVER), "/v1/drivers/me", null), 200);
        assertThat(driver.get("verification").asString()).isEqualTo("VERIFIED");
        assertThat(driver.get("vehicles")).hasSize(1);

        String rider = bearer(FIRST_RIDER, UserRole.RIDER);
        JsonNode profile = assertAnswered("GET", "/v1/riders/me", call("GET", rider, "/v1/riders/me", null), 200);
        assertThat(profile.get("default_payment_method_id").asString()).isEqualTo(FIRST_RIDERS_CASH.toString());
        assertAnswered("GET", "/v1/riders/me/payment-methods", call("GET", rider, "/v1/riders/me/payment-methods",
                null), 200);
        assertAnswered("GET", "/v1/riders/me/places", call("GET", rider, "/v1/riders/me/places", null), 200);
    }

    private String bearer(UUID userId, UserRole role) {
        return "Bearer " + accessTokens.issue(userId, Set.of(role)).value();
    }

    @Test
    void aSeededDriverGoesOnlineStreamsLocationsAndIsFoundNearby() {
        String driver = bearer(THIRD_DRIVER, UserRole.DRIVER);
        JsonNode vehicle = assertAnswered("GET", "/v1/drivers/me", call("GET", driver, "/v1/drivers/me", null), 200)
                .get("vehicles").get(0);
        String category = vehicle.get("category").asString();
        assertAnswered("POST", "/v1/drivers/me/online", postJson("/v1/drivers/me/online", Map.of("Authorization",
                driver, "Idempotency-Key", UUID.randomUUID().toString()), "{\"vehicle_id\": \"%s\"}".formatted(
                vehicle.get("id").asString())), 200);

        JsonNode streamed = assertAnswered("POST", "/v1/drivers/me/location", call("POST", driver,
                "/v1/drivers/me/location", """
                        {"updates": [
                          {"seq": 1, "lat": 12.97210, "lon": 77.64060, "accuracy_m": 6, "device_time": "%1$s"},
                          {"seq": 2, "lat": 12.97200, "lon": 77.64090, "accuracy_m": 6, "device_time": "%1$s"},
                          {"seq": 3, "lat": 12.97194, "lon": 77.64115, "accuracy_m": 5, "device_time": "%1$s"}]}
                        """.formatted(Instant.now())), 200);
        assertThat(streamed.get("applied").asInt()).isEqualTo(3);

        GeoPoint pickup = new GeoPoint(12.97300, 77.64200);
        assertThat(index.nearby("blr", category, pickup, 2_000, 5)).extracting(Candidate::driverId)
                .contains(THIRD_DRIVER);
        JsonNode quote = assertAnswered("POST", "/v1/quotes", call("POST", bearer(FIRST_RIDER, UserRole.RIDER),
                "/v1/quotes", """
                        {"pickup": {"lat": %s, "lon": %s}, "dropoff": {"lat": 12.93524, "lon": 77.62448},
                         "category": "%s"}
                        """.formatted(pickup.lat(), pickup.lon(), category)), 201);
        assertThat(quote.has("pickup_eta_s")).isTrue();

        assertAnswered("POST", "/v1/drivers/me/offline", postJson("/v1/drivers/me/offline", Map.of("Authorization",
                driver, "Idempotency-Key", UUID.randomUUID().toString()), "{}"), 200);
        assertThat(index.nearby("blr", category, pickup, 2_000, 5)).extracting(Candidate::driverId)
                .doesNotContain(THIRD_DRIVER);
    }

    @Test
    void aSeededRiderQuotesTheOpenApiExampleTripAtTheSeededMiniPrices() throws IOException {
        JsonNode quote = assertAnswered("POST", "/v1/quotes", call("POST", bearer(FIRST_RIDER, UserRole.RIDER),
                "/v1/quotes", """
                        {"pickup": {"lat": 12.97194, "lon": 77.64115}, "dropoff": {"lat": 12.93524, "lon": 77.62448},
                         "category": "MINI"}
                        """), 201);

        assertThat(quote.get("city_id").asString()).isEqualTo("blr");
        assertThat(quote.get("surge_multiplier").decimalValue()).satisfiesAnyOf(
                multiplier -> assertThat(multiplier).isEqualByComparingTo("1.00"),
                multiplier -> assertThat(multiplier).isEqualByComparingTo("1.30"));
        assertThat(jdbc.sql("SELECT fare_rule_id, fee_rule_id, pickup_zone FROM pricing.quotes WHERE id = :id")
                .param("id", UUID.fromString(quote.get("id").asString())).query().singleRow())
                .containsEntry("fare_rule_id", UUID.fromString("0199a3f0-0006-7000-8000-000000000002"))
                .containsEntry("fee_rule_id", UUID.fromString("0199a3f0-0006-7000-8000-000000000012"))
                .containsEntry("pickup_zone", H3Core.newInstance().latLngToCellAddress(12.97194, 77.64115, 7));
    }
}

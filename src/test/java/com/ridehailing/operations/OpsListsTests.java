package com.ridehailing.operations;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.Idempotency;
import com.ridehailing.ride.RideView;
import com.ridehailing.ride.db.FlagRepository;
import com.ridehailing.ride.db.FlagRepository.FlagKind;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestRides.AssignedRide;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** FR-O2, LLD §13.5: rides by status and city, drivers by availability, and the review queue. */
class OpsListsTests extends IntegrationTest {

    @Autowired
    TestCities cities;

    @Autowired
    TestPrices prices;

    @Autowired
    TestRides rides;

    @Autowired
    TestUsers users;

    @Autowired
    FlagRepository flags;

    @Autowired
    JdbcClient jdbc;

    private TestUser ops;

    @BeforeEach
    void operations() {
        ops = users.create(UserRole.OPS);
    }

    @Test
    void operationsListRidesByStatusAndCityNewestFirst() {
        TestCity city = city();
        RideView searching = rides.book(rides.rider("Rider").id(), city, city.at(0.1, 0.1), "MINI");
        AssignedRide assigned = rides.assigned(city, city.at(0.2, 0.2), city.at(0.2, 0.2));
        TestUser canceller = rides.rider("Rider");
        RideView cancelled = rides.book(canceller.id(), city, city.at(0.3, 0.3), "MINI");
        assertThat(postJson("/v1/rides/" + cancelled.id() + "/cancel", Map.of("Authorization",
                canceller.authorization(), Idempotency.HEADER, UUID.randomUUID().toString()), "{}").statusCode())
                .isEqualTo(200);

        JsonNode all = list("/v1/ops/rides?city_id=" + city.id());
        JsonNode active = list("/v1/ops/rides?city_id=" + city.id() + "&status=SEARCHING,DRIVER_ASSIGNED");
        JsonNode ended = list("/v1/ops/rides?status=CANCELLED_BY_RIDER&city_id=" + city.id());

        assertThat(ids(all)).containsExactly(cancelled.id(), assigned.id(), searching.id());
        assertThat(all.get("items").get(1).get("status").asString()).isEqualTo("DRIVER_ASSIGNED");
        assertThat(all.get("items").get(1).has("pin")).as("never the PIN").isFalse();
        assertThat(all.get("items").get(1).get("driver").get("first_name").isString()).isTrue();
        assertThat(all.has("next_cursor")).isFalse();
        assertThat(ids(active)).containsExactly(assigned.id(), searching.id());
        assertThat(ids(ended)).containsExactly(cancelled.id());
        JsonNode firstPage = list("/v1/ops/rides?city_id=" + city.id() + "&limit=2");
        JsonNode secondPage = list("/v1/ops/rides?city_id=" + city.id() + "&limit=2&cursor="
                + firstPage.get("next_cursor").asString());
        assertThat(ids(firstPage)).containsExactly(cancelled.id(), assigned.id());
        assertThat(ids(secondPage)).containsExactly(searching.id());
        assertThat(secondPage.has("next_cursor")).isFalse();
        assertThat(list("/v1/ops/rides?city_id=" + city.id() + "&limit=3").has("next_cursor"))
                .as("a last page that is exactly full").isFalse();
        assertProblem("GET", "/v1/ops/rides", getAs(ops.authorization(), "/v1/ops/rides?status=LOST"), 400,
                "VALIDATION_FAILED");
        assertProblem("GET", "/v1/ops/rides", getAs(ops.authorization(), "/v1/ops/rides?cursor=nope"), 400,
                "VALIDATION_FAILED");
        assertProblem("GET", "/v1/ops/rides", getAs(canceller.authorization(), "/v1/ops/rides"), 403,
                "FORBIDDEN");
    }

    @Test
    void operationsListDriversByAvailability() {
        TestCity city = city();
        TestDriver available = rides.onlineAt(city, "MINI", city.at(0.1, 0.1));
        TestDriver offline = rides.onlineAt(city, "MINI", city.at(0.5, 0.5));
        assertThat(postJson("/v1/drivers/me/offline", Map.of("Authorization", offline.authorization(),
                Idempotency.HEADER, UUID.randomUUID().toString()), "{}").statusCode()).isEqualTo(200);
        AssignedRide ride = rides.assigned(city, city.at(0.3, 0.3), city.at(0.3, 0.3));

        JsonNode all = drivers("/v1/ops/drivers?city_id=" + city.id());

        assertThat(driverIds(all)).containsExactly(ride.driver().id(), offline.id(), available.id());
        assertThat(all.get("items").get(0).get("ride_id").asString()).isEqualTo(ride.id().toString());
        assertThat(driverIds(drivers("/v1/ops/drivers?city_id=" + city.id() + "&status=AVAILABLE")))
                .containsExactly(available.id());
        assertThat(driverIds(drivers("/v1/ops/drivers?city_id=" + city.id() + "&status=OFFLINE")))
                .containsExactly(offline.id());
        assertThat(driverIds(drivers("/v1/ops/drivers?status=ASSIGNED&city_id=" + city.id())))
                .containsExactly(ride.driver().id());
        List<UUID> paged = new ArrayList<>();
        String cursor = "";
        int pages = 0;
        do {
            JsonNode page = drivers("/v1/ops/drivers?limit=1&city_id=" + city.id() + cursor);
            paged.addAll(driverIds(page));
            pages++;
            cursor = page.has("next_cursor") ? "&cursor=" + page.get("next_cursor").asString() : null;
        } while (cursor != null);
        assertThat(paged).containsExactly(ride.driver().id(), offline.id(), available.id());
        assertThat(pages).as("no cursor on the last page").isEqualTo(3);
        assertProblem("GET", "/v1/ops/drivers", getAs(ops.authorization(), "/v1/ops/drivers?status=BUSY"), 400,
                "VALIDATION_FAILED");
    }

    @Test
    void theReviewQueueListsFlagsAndResolvesEachOnce() {
        TestCity city = city();
        RideView earlier = rides.book(rides.rider("Rider").id(), city, city.at(0.2, 0.2), "MINI");
        flags.open(earlier.id(), FlagKind.STUCK, "{\"status\": \"SEARCHING\"}");
        RideView ride = rides.book(rides.rider("Rider").id(), city, city.at(0.1, 0.1), "MINI");
        flags.open(ride.id(), FlagKind.STUCK, "{\"status\": \"SEARCHING\"}");
        flags.open(ride.id(), FlagKind.PIN_LOCKED, "{}");
        UUID stuck = flagId(ride.id(), "STUCK");

        JsonNode open = flagList("/v1/ops/flags?kind=STUCK");
        JsonNode resolved = assertAnswered("POST", "/v1/ops/flags/{flag_id}/resolve", resolve(stuck,
                "{\"resolution\": \"Rider rebooked\"}"), 200);
        JsonNode afterwards = flagList("/v1/ops/flags?kind=STUCK&open=false");

        assertThat(flagIds(open).subList(0, 2)).as("newest first").containsExactly(stuck,
                flagId(earlier.id(), "STUCK"));
        assertThat(flagIds(flagList("/v1/ops/flags?kind=STUCK"))).as("resolved, so no longer open")
                .doesNotContain(stuck).contains(flagId(earlier.id(), "STUCK"));
        assertThat(flagIds(flagList("/v1/ops/flags?kind=PIN_LOCKED&open=false"))).as("still open")
                .doesNotContain(flagId(ride.id(), "PIN_LOCKED"));

        JsonNode listed = open.get("items").get(0);
        assertThat(listed.get("id").asString()).isEqualTo(stuck.toString());
        assertThat(listed.get("ride_id").asString()).isEqualTo(ride.id().toString());
        assertThat(listed.get("details").get("status").asString()).isEqualTo("SEARCHING");
        assertThat(listed.has("resolved_at")).isFalse();
        assertThat(resolved.get("resolution").asString()).isEqualTo("Rider rebooked");
        assertThat(resolved.has("resolved_at")).isTrue();
        assertThat(jdbc.sql("SELECT resolved_by FROM ride.flags WHERE id = :id").param("id", stuck)
                .query(UUID.class).single()).isEqualTo(ops.id());
        assertThat(afterwards.get("items").get(0).get("id").asString()).isEqualTo(stuck.toString());
        assertThat(flagList("/v1/ops/flags").get("items").get(0).get("kind").asString()).as("open ones by default")
                .isEqualTo("PIN_LOCKED");
        assertThat(jdbc.sql("""
                        SELECT action || ' by ' || actor_type || ': ' || reason FROM audit.audit_log
                        WHERE entity_type = 'flag' AND entity_id = :id
                        """).param("id", stuck.toString()).query(String.class).list())
                .containsExactly("flag.resolve by OPS: Rider rebooked");
        assertProblem("POST", "/v1/ops/flags/{flag_id}/resolve", resolve(stuck, "{\"resolution\": \"Again\"}"), 409,
                "FLAG_ALREADY_RESOLVED");
        assertProblem("POST", "/v1/ops/flags/{flag_id}/resolve", resolve(UUID.randomUUID(),
                "{\"resolution\": \"Unknown\"}"), 404, "NOT_FOUND");
        assertProblem("POST", "/v1/ops/flags/{flag_id}/resolve", resolve(flagId(ride.id(), "PIN_LOCKED"),
                "{\"resolution\": \"\"}"), 400, "VALIDATION_FAILED");
        assertProblem("GET", "/v1/ops/flags", getAs(ops.authorization(), "/v1/ops/flags?kind=LOST"), 400,
                "VALIDATION_FAILED");
    }

    private TestCity city() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        return city;
    }

    private JsonNode list(String path) {
        return assertAnswered("GET", "/v1/ops/rides", getAs(ops.authorization(), path), 200);
    }

    private JsonNode drivers(String path) {
        return assertAnswered("GET", "/v1/ops/drivers", getAs(ops.authorization(), path), 200);
    }

    private JsonNode flagList(String path) {
        return assertAnswered("GET", "/v1/ops/flags", getAs(ops.authorization(), path), 200);
    }

    private HttpResponse<String> resolve(UUID flagId, String body) {
        return postJson("/v1/ops/flags/" + flagId + "/resolve", Map.of("Authorization", ops.authorization()), body);
    }

    private UUID flagId(UUID rideId, String kind) {
        return jdbc.sql("SELECT id FROM ride.flags WHERE ride_id = :ride AND kind = :kind")
                .param("ride", rideId).param("kind", kind).query(UUID.class).single();
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(UUID.fromString(item.get("id").asString())));
        return ids;
    }

    private static List<UUID> driverIds(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(UUID.fromString(item.get("driver_id").asString())));
        return ids;
    }

    private static List<UUID> flagIds(JsonNode page) {
        return ids(page);
    }
}

package com.ridehailing.rider;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.shared.UserRole;
import com.ridehailing.support.Eventually;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.Postgis;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** LLD §13.3: the rider's own profile, saved places and payment methods. */
class RiderProfileTests extends IntegrationTest {

    private static final String ME = "/v1/riders/me";
    private static final String PLACES = "/v1/riders/me/places";
    private static final String PLACE = "/v1/riders/me/places/{place_id}";
    private static final String METHODS = "/v1/riders/me/payment-methods";
    private static final String METHOD = "/v1/riders/me/payment-methods/{method_id}";
    private static final String DEFAULT = "/v1/riders/me/payment-methods/{method_id}/default";

    @Autowired
    private TestUsers users;

    @Autowired
    private JdbcClient jdbc;

    private TestUser rider;

    @BeforeEach
    void signIn() {
        rider = users.create(UserRole.RIDER);
    }

    @Test
    void theFirstCallCreatesTheProfileWithCashAsTheDefault() {
        JsonNode profile = assertAnswered("GET", ME, call("GET", rider.authorization(), ME, null), 200);
        JsonNode methods = assertAnswered("GET", METHODS, call("GET", rider.authorization(), METHODS, null), 200);

        assertThat(profile.get("id").asString()).isEqualTo(rider.id().toString());
        assertThat(methods.get("items")).hasSize(1);
        JsonNode cash = methods.get("items").get(0);
        assertThat(cash.get("type").asString()).isEqualTo("CASH");
        assertThat(cash.get("is_default").asBoolean()).isTrue();
        assertThat(profile.get("default_payment_method_id").asString()).isEqualTo(cash.get("id").asString());
        assertThat(json(call("GET", rider.authorization(), METHODS, null)).get("items")).hasSize(1);
    }

    @Test
    void theProfileTakesPartialUpdates() {
        JsonNode named = assertAnswered("PATCH", ME, call("PATCH", rider.authorization(), ME,
                "{\"first_name\": \"Asha\"}"), 200);
        JsonNode emailed = assertAnswered("PATCH", ME, call("PATCH", rider.authorization(), ME,
                "{\"email\": \"asha@example.com\"}"), 200);

        assertThat(named.get("first_name").asString()).isEqualTo("Asha");
        assertThat(emailed.get("first_name").asString()).isEqualTo("Asha");
        assertProblem("PATCH", ME, call("PATCH", rider.authorization(), ME, "{}"), 400, "VALIDATION_FAILED");
        assertProblem("PATCH", ME, call("PATCH", rider.authorization(), ME, "{\"email\": \"not-an-email\"}"), 400,
                "VALIDATION_FAILED");
    }

    @Test
    void placesHaveUniqueLabelsAndBelongToTheirRider() {
        JsonNode home = assertAnswered("POST", PLACES, call("POST", rider.authorization(), PLACES, place("home")), 201);
        assertAnswered("GET", PLACES, call("GET", rider.authorization(), PLACES, null), 200);
        assertProblem("POST", PLACES, call("POST", rider.authorization(), PLACES, place("home")), 409, "ALREADY_EXISTS");

        String path = PLACES + "/" + home.get("id").asString();
        assertProblem("DELETE", PLACE, call("DELETE", users.create(UserRole.RIDER).authorization(), path, null), 404,
                "NOT_FOUND");
        assertAnswered("DELETE", PLACE, call("DELETE", rider.authorization(), path, null), 204);
        assertProblem("DELETE", PLACE, call("DELETE", rider.authorization(), path, null), 404, "NOT_FOUND");
    }

    @Test
    void aPlaceNeedsACompleteValidLocation() {
        assertProblem("POST", PLACES, call("POST", rider.authorization(), PLACES,
                "{\"label\": \"gym\", \"name\": \"Gym\", \"location\": {\"lat\": 91, \"lon\": 77.6}}"), 400,
                "VALIDATION_FAILED");
        // A missing coordinate must not become 0: Jackson 3 refuses absent primitives while reading the body.
        assertProblem("POST", PLACES, call("POST", rider.authorization(), PLACES,
                "{\"label\": \"gym\", \"name\": \"Gym\", \"location\": {\"lon\": 77.6}}"), 400, "MALFORMED_REQUEST");
        assertThat(json(call("GET", rider.authorization(), PLACES, null)).get("items")).isEmpty();
    }

    @Test
    void aRiderSavesAtMostTenPlaces() {
        for (int place = 1; place <= 10; place++) {
            assertThat(call("POST", rider.authorization(), PLACES, place("place " + place)).statusCode()).isEqualTo(201);
        }

        assertProblem("POST", PLACES, call("POST", rider.authorization(), PLACES, place("one more")), 409,
                "PLACES_LIMIT_REACHED");
    }

    @Test
    void concurrentSavesCantPassTheLimitTogether() throws Exception {
        for (int place = 1; place <= 9; place++) {
            call("POST", rider.authorization(), PLACES, place("place " + place));
        }
        List<Future<HttpResponse<String>>> saves = new ArrayList<>();

        // Blocking inserts from another session lines both saves up after they counted, so without the rider row's
        // lock both would see nine places.
        try (Connection blocker = Postgis.connection(); var executor = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            blocker.createStatement().execute("LOCK TABLE rider.saved_places IN EXCLUSIVE MODE");
            saves.add(executor.submit(() -> call("POST", rider.authorization(), PLACES, place("tenth"))));
            saves.add(executor.submit(() -> call("POST", rider.authorization(), PLACES, place("eleventh"))));
            Eventually.within(Duration.ofSeconds(10), () -> assertThat(sessionsWaitingForALock()).isEqualTo(2));
            blocker.rollback();
            List<Integer> statuses = new ArrayList<>();
            for (Future<HttpResponse<String>> save : saves) {
                statuses.add(save.get(30, TimeUnit.SECONDS).statusCode());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        }

        assertThat(json(call("GET", rider.authorization(), PLACES, null)).get("items")).hasSize(10);
    }

    @Test
    void removingTheDefaultMethodMakesCashTheDefaultAndCashStays() {
        JsonNode card = assertAnswered("POST", METHODS, call("POST", rider.authorization(), METHODS,
                "{\"type\": \"CARD\", \"provider_token\": \"tok_ok\", \"display\": \"Visa •• 4242\"}"), 201);
        assertThat(card.get("is_default").asBoolean()).isFalse();
        String cardPath = METHODS + "/" + card.get("id").asString();

        JsonNode chosen = assertAnswered("POST", DEFAULT, call("POST", rider.authorization(), cardPath + "/default",
                null), 200);
        assertThat(chosen.get("is_default").asBoolean()).isTrue();
        assertThat(json(call("GET", rider.authorization(), ME, null)).get("default_payment_method_id").asString())
                .isEqualTo(card.get("id").asString());

        assertAnswered("DELETE", METHOD, call("DELETE", rider.authorization(), cardPath, null), 204);
        JsonNode methods = json(call("GET", rider.authorization(), METHODS, null));
        assertThat(methods.get("items")).hasSize(1);
        JsonNode cash = methods.get("items").get(0);
        assertThat(cash.get("is_default").asBoolean()).isTrue();

        assertProblem("DELETE", METHOD, call("DELETE", rider.authorization(), METHODS + "/" + cash.get("id").asString(),
                null), 422, "PAYMENT_METHOD_INVALID");
        assertProblem("POST", DEFAULT, call("POST", rider.authorization(), cardPath + "/default", null), 404,
                "NOT_FOUND");
        assertProblem("DELETE", METHOD, call("DELETE", users.create(UserRole.RIDER).authorization(),
                METHODS + "/" + cash.get("id").asString(), null), 404, "NOT_FOUND");
        assertProblem("POST", METHODS, call("POST", rider.authorization(), METHODS,
                "{\"type\": \"CASH\", \"provider_token\": \"tok_ok\", \"display\": \"Cash\"}"), 400,
                "VALIDATION_FAILED");
    }

    @Test
    void onlyRidersHaveRiderProfiles() {
        String driver = users.create(UserRole.DRIVER).authorization();

        assertProblem("GET", ME, call("GET", driver, ME, null), 403, "FORBIDDEN");
        assertProblem("GET", METHODS, call("GET", null, METHODS, null), 401, "UNAUTHENTICATED");
    }

    private long sessionsWaitingForALock() {
        return jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                        + " AND datname = current_database()")
                .query(Long.class)
                .single();
    }

    private static String place(String label) {
        return """
                {"label": "%s", "name": "12th Main, Indiranagar", "location": {"lat": 12.97194, "lon": 77.64115}}
                """.formatted(label);
    }
}

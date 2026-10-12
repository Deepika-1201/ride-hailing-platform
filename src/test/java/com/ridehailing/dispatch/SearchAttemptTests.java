package com.ridehailing.dispatch;

import static com.ridehailing.support.EventContract.outboxEvents;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.Candidate;
import com.ridehailing.geography.RankingWeights;
import com.ridehailing.geography.RankingExperiment;
import com.ridehailing.geography.app.CategoryAdministration;
import com.ridehailing.geography.db.CategoryRepository.Settings;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.ride.RideView;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.EventContract;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.Postgis;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestUsers.TestUser;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** LLD §8.3: one attempt per transaction, the nearest available driver reserved, the decision recorded. */
class SearchAttemptTests extends IntegrationTest {

    private static final double METRES_PER_DEGREE = 6_371_008.8 * Math.PI / 180;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestRides rides;

    @Autowired
    private LiveIndex index;

    @Autowired
    private DispatchApi dispatch;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CategoryAdministration categories;

    private TestCity city;
    private GeoPoint pickup;
    private TestUser rider;

    @BeforeEach
    void setUp() {
        city = cities.create("MINI", "SEDAN");
        prices.price(city.id(), "MINI");
        pickup = city.at(0.1, 0.1);
        rider = rides.rider("Aditi");
    }

    @Test
    void theNearestAvailableDriverIsReservedAndOffered() {
        rides.onlineAt(city, "MINI", north(300));
        TestDriver near = rides.onlineAt(city, "MINI", north(100));
        RideView ride = rides.book(rider.id(), city, pickup, "MINI");

        search();

        Map<String, Object> offer = jdbc.sql("""
                        SELECT id, driver_id, status, attempt, rank, distance_m,
                               extract(epoch FROM expires_at - created_at)::int AS ttl_s
                        FROM dispatch.offers WHERE ride_id = :rideId
                        """).param("rideId", ride.id()).query().singleRow();
        UUID offerId = (UUID) offer.get("id");
        assertThat(offer).containsEntry("driver_id", near.id()).containsEntry("status", "PENDING")
                .containsEntry("attempt", 1).containsEntry("rank", 1).containsEntry("distance_m", 100)
                .containsEntry("ttl_s", 15);
        assertThat(jdbc.sql("SELECT status || ' ' || offer_id FROM dispatch.driver_availability WHERE driver_id = :id")
                .param("id", near.id()).query(String.class).single()).isEqualTo("OFFERED " + offerId);
        assertThat(jdbc.sql("SELECT (due_at IS NULL) || ' ' || attempt FROM dispatch.search_tasks WHERE ride_id = :id")
                .param("id", ride.id()).query(String.class).single()).isEqualTo("true 1");
        assertThat(rides.timers().exists("OFFER_EXPIRY", offerId)).isTrue();
        JsonNode decision = decision(ride.id());
        assertThat(decision.get("outcome").asString()).isEqualTo("OFFERED");
        assertThat(decision.get("strategy").asString()).isEqualTo("nearest");
        JsonNode detail = decision.get("detail");
        assertThat(detail.get("candidates").get(0).get("driver_id").asString()).isEqualTo(near.id().toString());
        assertThat(detail.get("candidates")).hasSize(2);
        assertThat(tries(detail)).containsExactly(near.id() + " RESERVED");
        assertThat(detail.get("excluded").get("already_offered").asInt()).isZero();
        assertThat(outboxEvents(jdbc, "OfferCreated", offerId)).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("correlation_id").asString()).isEqualTo(ride.id().toString());
        });
        assertThat(index.nearby(city.id(), "MINI", pickup, 1_000, 5)).extracting(Candidate::driverId)
                .doesNotContain(near.id());
        assertThat(jdbc.sql("SELECT offers FROM dispatch.driver_stats WHERE driver_id = :id").param("id", near.id())
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void withoutCandidatesTheSearchWidensAndWaitsFiveSeconds() {
        RideView ride = rides.book(rider.id(), city, pickup, "MINI");

        search();

        assertThat(decision(ride.id()).get("outcome").asString()).isEqualTo("NO_CANDIDATES");
        assertThat(task(ride.id())).isEqualTo("3000 1 4-6");
    }

    @Test
    void theRadiusStopsWideningAtItsMaximum() {
        RideView ride = rides.book(rider.id(), city, pickup, "MINI");
        jdbc.sql("UPDATE dispatch.search_tasks SET radius_m = 5500 WHERE ride_id = :id").param("id", ride.id())
                .update();

        search();

        assertThat(task(ride.id())).isEqualTo("6000 1 4-6");
        makeDue(ride.id());

        search();

        assertThat(task(ride.id())).isEqualTo("6000 2 4-6");
    }

    @Test
    void lostReservationsRetryWithinASecondAtAWiderRadius() {
        TestDriver busy = rides.onlineAt(city, "MINI", north(100));
        assignElsewhere(busy);
        RideView ride = rides.book(rider.id(), city, pickup, "MINI");

        search();

        JsonNode decision = decision(ride.id());
        assertThat(decision.get("outcome").asString()).isEqualTo("ALL_RESERVATIONS_LOST");
        assertThat(tries(decision.get("detail"))).containsExactly(busy.id() + " LOST");
        assertThat(task(ride.id())).isEqualTo("3000 1 0-1");
    }

    @Test
    void atMostFiveCandidatesAreTriedInOneAttempt() {
        for (int i = 1; i <= 6; i++) {
            assignElsewhere(rides.onlineAt(city, "MINI", north(100 * i)));
        }
        RideView ride = rides.book(rider.id(), city, pickup, "MINI");

        search();

        assertThat(decision(ride.id()).get("detail").get("tries")).hasSize(5);
    }

    @Test
    void aDriverIsNeverOfferedTheSameRideTwice() {
        TestDriver near = rides.onlineAt(city, "MINI", north(100));
        RideView ride = rides.book(rider.id(), city, pickup, "MINI");
        search();
        TestRides.asApi(() -> dispatch.decline(rides.pendingOffer(ride.id()), near.id()));
        TestDriver next = rides.onlineAt(city, "MINI", north(500));

        search();

        assertThat(jdbc.sql("SELECT driver_id FROM dispatch.offers WHERE ride_id = :id AND status = 'PENDING'")
                .param("id", ride.id()).query(UUID.class).single()).isEqualTo(next.id());
        assertThat(decision(ride.id()).get("detail").get("excluded").get("already_offered").asInt()).isEqualTo(1);
    }

    @Test
    void driversOfAnotherCategoryAreNotOffered() {
        rides.onlineAt(city, "SEDAN", north(100));
        RideView ride = rides.book(rider.id(), city, pickup, "MINI");

        search();

        assertThat(decision(ride.id()).get("outcome").asString()).isEqualTo("NO_CANDIDATES");
    }

    @Test
    void aRideThatStoppedSearchingLosesItsTask() {
        rides.onlineAt(city, "MINI", north(100));
        RideView ride = rides.book(rider.id(), city, pickup, "MINI");
        jdbc.sql("UPDATE ride.rides SET status = 'CANCELLED_BY_RIDER' WHERE id = :id").param("id", ride.id()).update();

        search();

        assertThat(jdbc.sql("SELECT count(*) FROM dispatch.search_tasks WHERE ride_id = :id").param("id", ride.id())
                .query(Long.class).single()).isZero();
        assertThat(rides.pendingOffer(ride.id())).isNull();
    }

    @Test
    void etaWithoutAnEnabledMatrixProviderRecordsDistanceFallback() {
        jdbc.sql("UPDATE geography.city_categories SET ranker = 'eta' WHERE city_id = :city AND category = 'MINI'")
                .param("city", city.id()).update();
        TestDriver nearest = rides.onlineAt(city, "MINI", north(100));
        rides.onlineAt(city, "MINI", north(900));
        RideView ride = rides.book(rider.id(), city, pickup, "MINI");

        search();

        JsonNode decision = decision(ride.id());
        assertThat(decision.get("strategy").asString()).isEqualTo("eta");
        assertThat(decision.get("detail").get("score_unit").asString()).isEqualTo("METRES");
        assertThat(decision.get("detail").get("ranking_fallback").asString()).isEqualTo("MATRIX_UNAVAILABLE");
        assertThat(jdbc.sql("SELECT driver_id FROM dispatch.offers WHERE ride_id = :id AND status = 'PENDING'")
                .param("id", ride.id()).query(UUID.class).single()).isEqualTo(nearest.id());
    }

        @Test
        void anExperimentAssignmentSurvivesRetryAndConfigurationChangesAndNewSearchesUseTheUpdate() {
        Caller admin = new Caller(Ids.newId(), Set.of(UserRole.ADMIN));
        RankingWeights originalWeights = new RankingWeights(1.5, 50, 4, 20);
        categories.put(admin, new Settings(city.id(), "MINI", true, 15, 180, 2000, 1000, 6000, "nearest", 0,
            originalWeights, new RankingExperiment("weighted-trial", "weighted", 100)), 0);
        RideView ride = rides.book(rider.id(), city, pickup, "MINI");
        search();
        JsonNode first = decision(ride.id());
        JsonNode assignment = first.get("detail").get("ranking_assignment");
        assertThat(first.get("strategy").asString()).isEqualTo("weighted");
        assertThat(assignment.get("category_version").asInt()).isEqualTo(1);
        assertThat(assignment.get("experiment").get("id").asString()).isEqualTo("weighted-trial");
        assertThat(assignment.get("experiment").get("percentage").asInt()).isEqualTo(100);
        assertThat(assignment.get("weights")).isEqualTo(JSON.valueToTree(originalWeights));
        categories.put(admin, new Settings(city.id(), "MINI", true, 20, 180, 2000, 1000, 6000, "eta", 0,
            RankingWeights.DEFAULT, new RankingExperiment("weighted-trial", "weighted", 0)), 1);
        TestDriver driver = rides.onlineAt(city, "MINI", north(100));
        makeDue(ride.id());
        search();
        JsonNode retried = decision(ride.id());
        assertThat(retried.get("strategy").asString()).isEqualTo("weighted");
        assertThat(retried.get("detail").get("ranking_assignment")).isEqualTo(assignment);
        assertThat(retried.get("detail").get("ranking_fallback").asString()).isEqualTo("MATRIX_UNAVAILABLE");
        UUID offer = rides.pendingOffer(ride.id());
        assertThat(outboxEvents(jdbc, "OfferCreated", offer)).singleElement().satisfies(event ->
            assertThat(event.get("payload").get("strategy").asString()).isEqualTo("weighted"));
        TestRides.asApi(() -> dispatch.decline(offer, driver.id()));
        search();
        assertThat(decision(ride.id()).get("detail").get("ranking_assignment")).isEqualTo(assignment);

        RideView fresh = rides.book(rides.rider("New experiment rider").id(), city, pickup, "MINI");
        search();
        JsonNode newAssignment = decision(fresh.id()).get("detail").get("ranking_assignment");
        assertThat(newAssignment.get("strategy").asString()).isEqualTo("eta");
        assertThat(newAssignment.get("category_version").asInt()).isEqualTo(2);
        assertThat(newAssignment.get("experiment").get("percentage").asInt()).isZero();
        assertThat(rides.violations(city.id())).isEmpty();
        }

        /** §6.2: pollers skip a task another one holds rather than queue behind it. */
    @Test
    void aTaskAnotherPollerHoldsIsSkipped() throws Exception {
        rides.onlineAt(city, "MINI", north(100));
        RideView ride = rides.book(rider.id(), city, pickup, "MINI");
        rides.onlyDueIn(city.id());

        try (Connection gate = holding(ride.id())) {
            assertThat(assertTimeoutPreemptively(Duration.ofSeconds(5), () -> rides.search())).isZero();
            gate.rollback();
        }

        assertThat(rides.search()).isEqualTo(1);
        assertThat(rides.pendingOffer(ride.id())).isNotNull();
    }

    /** §6.2: a cancel removes the task without waiting for the poller holding it, which then finds the ride gone. */
    @Test
    void aCancelDoesntWaitForThePollerHoldingTheTask() throws Exception {
        rides.onlineAt(city, "MINI", north(100));
        RideView ride = rides.book(rider.id(), city, pickup, "MINI");
        rides.onlyDueIn(city.id());

        try (Connection gate = holding(ride.id())) {
            assertThat(assertTimeoutPreemptively(Duration.ofSeconds(5), () -> postJson("/v1/rides/" + ride.id()
                    + "/cancel", Map.of("Authorization", rider.authorization(), Idempotency.HEADER,
                    UUID.randomUUID().toString()), "{}")).statusCode()).isEqualTo(200);
            gate.rollback();
        }

        assertThat(rides.search()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM dispatch.search_tasks WHERE ride_id = :id").param("id", ride.id())
                .query(Long.class).single()).isZero();
        assertThat(rides.pendingOffer(ride.id())).isNull();
    }

    /** A session of its own that holds the ride's task, as a poller does during an attempt. */
    private static Connection holding(UUID rideId) throws SQLException {
        Connection gate = Postgis.connection();
        gate.setAutoCommit(false);
        try (var lock = gate.prepareStatement("SELECT 1 FROM dispatch.search_tasks WHERE ride_id = ? FOR UPDATE")) {
            lock.setObject(1, rideId);
            lock.executeQuery().close();
        }
        return gate;
    }

    private void search() {
        rides.onlyDueIn(city.id());
        rides.search();
    }

    private void makeDue(UUID rideId) {
        jdbc.sql("UPDATE dispatch.search_tasks SET due_at = now() WHERE ride_id = :id").param("id", rideId).update();
    }

    /** The driver's database row is busy while the live index still shows them available, as before a mirror write. */
    private void assignElsewhere(TestDriver driver) {
        jdbc.sql("UPDATE dispatch.driver_availability SET status = 'ASSIGNED', ride_id = :rideId WHERE driver_id = :id")
                .param("rideId", Ids.newId()).param("id", driver.id()).update();
    }

    private static List<String> tries(JsonNode detail) {
        return detail.get("tries").valueStream()
                .map(entry -> entry.get("driver_id").asString() + " " + entry.get("result").asString()).toList();
    }

    private JsonNode decision(UUID rideId) {
        List<JsonNode> decisions = new ArrayList<>(jdbc.sql("""
                        SELECT json_build_object('outcome', outcome, 'strategy', strategy, 'detail', detail)::text
                        FROM dispatch.decisions WHERE ride_id = :id ORDER BY attempt
                        """).param("id", rideId).query(String.class).list().stream().map(JSON::readTree).toList());
        assertThat(decisions).isNotEmpty();
        return decisions.getLast();
    }

    /** Radius, attempt, and whole seconds until due as a range. */
    private String task(UUID rideId) {
        return jdbc.sql("""
                        SELECT radius_m || ' ' || attempt || ' ' ||
                               CASE WHEN due_at - now() BETWEEN interval '4 seconds' AND interval '6 seconds' THEN '4-6'
                                    WHEN due_at - now() <= interval '1 second' THEN '0-1' ELSE (due_at - now())::text END
                        FROM dispatch.search_tasks WHERE ride_id = :id
                        """).param("id", rideId).query(String.class).single();
    }

    private GeoPoint north(double metres) {
        return new GeoPoint(pickup.lat() + metres / METRES_PER_DEGREE, pickup.lon());
    }
}

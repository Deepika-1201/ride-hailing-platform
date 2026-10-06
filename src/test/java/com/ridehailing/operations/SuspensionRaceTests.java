package com.ridehailing.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.Poller;
import com.ridehailing.ride.RideView;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.RaceRunner;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Race 9 and the suspension's race with a search attempt (LLD §17.2), each repeated in a city of its own and followed
 * by the invariant checks, I3 and I8 among them.
 */
class SuspensionRaceTests extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    TestCities cities;

    @Autowired
    TestPrices prices;

    @Autowired
    TestRides rides;

    @Autowired
    TestUsers users;

    @Autowired
    JdbcClient jdbc;

    /** Race 9: either the acceptance commits first and the ride goes on, or the suspension withdraws the offer. */
    @Test
    void suspendingADriverAsTheyAcceptEndsOneWayOrTheOther() throws Exception {
        TestUser ops = users.create(UserRole.OPS);
        Map<String, Integer> seen = new TreeMap<>();
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            TestCity city = city();
            TestDriver driver = rides.onlineAt(city, "MINI", city.at(0.1, 0.1));
            RideView ride = rides.book(rides.rider("Rider").id(), city, city.at(0.1, 0.1), "MINI");
            rides.onlyDueIn(city.id());
            rides.search();
            UUID offer = rides.pendingOffer(ride.id());
            assertThat(offer).as("repetition %d: an offer", repetition).isNotNull();

            List<String> outcomes = RaceRunner.staggered(() -> suspend(ops, driver), () -> accept(driver, offer));

            assertThat(outcomes.getFirst()).as("repetition %d", repetition).isEqualTo("200");
            boolean acceptedFirst = outcomes.getLast().equals("200");
            if (acceptedFirst) {
                assertThat(rides.rideStatus(ride.id())).as("repetition %d", repetition).isEqualTo("DRIVER_ASSIGNED");
                assertThat(availability(driver)).as("repetition %d", repetition)
                        .isEqualTo("ASSIGNED offline after the ride");
            } else {
                assertThat(outcomes.getLast()).as("repetition %d", repetition)
                        .isEqualTo("409 OFFER_NO_LONGER_AVAILABLE");
                assertThat(rides.offerStatus(offer)).as("repetition %d", repetition).isEqualTo("WITHDRAWN");
                assertThat(rides.rideStatus(ride.id())).as("repetition %d", repetition).isEqualTo("SEARCHING");
                assertThat(availability(driver)).as("repetition %d", repetition).isEqualTo("OFFLINE");
            }
            assertThat(rides.violations(city.id())).as("repetition %d", repetition).isEmpty();
            seen.merge(acceptedFirst ? "accepted first" : "suspended first", 1, Integer::sum);
        }
        RaceRunner.assertExplored(seen, "accepted first", "suspended first");
    }

    /**
     * The driver ends offline with no pending offer and the ride keeps searching. A suspension that met an offer
     * arriving between its read and its lock is refused, changes nothing, and succeeds when tried again.
     */
    @Test
    void suspendingADriverAsASearchOffersThemTheRideLeavesNoOfferOnThem() throws Exception {
        TestUser ops = users.create(UserRole.OPS);
        Map<String, Integer> seen = new TreeMap<>();
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            TestCity city = city();
            TestDriver driver = rides.onlineAt(city, "MINI", city.at(0.1, 0.1));
            RideView ride = rides.book(rides.rider("Rider").id(), city, city.at(0.1, 0.1), "MINI");
            rides.onlyDueIn(city.id());
            Poller poller = rides.searchTaskPoller();

            List<String> outcomes = RaceRunner.staggered(() -> suspend(ops, driver),
                    () -> String.valueOf(TestRides.asDispatch(poller::poll)));

            if (outcomes.getFirst().equals("409 INVALID_TRANSITION")) {
                assertThat(suspended(driver)).as("repetition %d: nothing changed", repetition).isFalse();
                assertThat(suspend(ops, driver)).as("repetition %d: tried again", repetition).isEqualTo("200");
                seen.merge("tried again", 1, Integer::sum);
            } else {
                assertThat(outcomes.getFirst()).as("repetition %d", repetition).isEqualTo("200");
            }
            assertThat(suspended(driver)).as("repetition %d", repetition).isTrue();
            assertThat(availability(driver)).as("repetition %d", repetition).isEqualTo("OFFLINE");
            assertThat(jdbc.sql("SELECT count(*) FROM dispatch.offers WHERE driver_id = :id AND status = 'PENDING'")
                    .param("id", driver.id()).query(Long.class).single()).as("repetition %d", repetition).isZero();
            assertThat(rides.rideStatus(ride.id())).as("repetition %d", repetition).isEqualTo("SEARCHING");
            assertThat(rides.violations(city.id())).as("repetition %d", repetition).isEmpty();
            UUID offer = jdbc.sql("SELECT id FROM dispatch.offers WHERE ride_id = :ride").param("ride", ride.id())
                    .query(UUID.class).optional().orElse(null);
            seen.merge(offer == null ? "suspended first" : "offer " + rides.offerStatus(offer), 1, Integer::sum);
        }
        RaceRunner.assertExplored(seen, "suspended first", "offer WITHDRAWN");
    }

    private TestCity city() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        return city;
    }

    private String suspend(TestUser ops, TestDriver driver) {
        return outcome(postJson("/v1/ops/drivers/" + driver.id() + "/suspend", Map.of("Authorization",
                ops.authorization(), Idempotency.HEADER, UUID.randomUUID().toString()), "{\"reason\": \"Race\"}"));
    }

    private String accept(TestDriver driver, UUID offerId) {
        return outcome(postJson("/v1/offers/" + offerId + "/accept", Map.of("Authorization", driver.authorization(),
                Idempotency.HEADER, UUID.randomUUID().toString()), "{}"));
    }

    private String availability(TestDriver driver) {
        return jdbc.sql("""
                        SELECT status || CASE WHEN offline_after_ride THEN ' offline after the ride' ELSE '' END
                        FROM dispatch.driver_availability WHERE driver_id = :id
                        """)
                .param("id", driver.id()).query(String.class).single();
    }

    private boolean suspended(TestDriver driver) {
        return jdbc.sql("SELECT suspended FROM driver.drivers WHERE id = :id").param("id", driver.id())
                .query(Boolean.class).single();
    }

    /** The status, with the problem code of an error. */
    private static String outcome(HttpResponse<String> response) {
        if (response.statusCode() < 400) {
            return String.valueOf(response.statusCode());
        }
        return response.statusCode() + " " + JSON.readTree(response.body()).path("code").asString();
    }
}

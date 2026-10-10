package com.ridehailing.ride;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.PushBus;
import com.ridehailing.platform.Transactions;
import com.ridehailing.ride.app.DriverCommands;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.PushInbox;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestUsers.TestUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** LLD §14.7: every transition pushes ride_status to the rider and to each driver concerned, after it commits. */
class RideStatusPushTests extends IntegrationTest {

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestRides rides;

    @Autowired
    private DispatchApi dispatch;

    @Autowired
    private PushBus push;

    @Autowired
    private DriverCommands driverCommands;

    @Autowired
    private Transactions transactions;

    @Autowired
    private JdbcClient jdbc;

    private TestCity city;
    private TestUser rider;
    private TestDriver driver;
    private PushInbox riderInbox;
    private PushInbox driverInbox;
    private RideView ride;

    @BeforeEach
    void setUp() {
        city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        rider = rides.rider("Aditi");
        driver = rides.onlineAt(city, "MINI", city.at(0.1, 0.101));
        riderInbox = PushInbox.open(push, PushBus.riderChannel(rider.id()));
        driverInbox = PushInbox.open(push, PushBus.driverChannel(driver.id()));
        ride = rides.book(rider.id(), city, city.at(0.1, 0.1), "MINI");
        rides.onlyDueIn(city.id());
        rides.search();
        TestRides.asApi(() -> dispatch.accept(rides.pendingOffer(ride.id()), driver.id()));
    }

    @AfterEach
    void closeInboxes() {
        riderInbox.close();
        driverInbox.close();
    }

    @Test
    void theRiderAndTheDriverHearOfEveryTransitionWithTheOthersSummary() {
        drive("arrive", "{}");
        drive("start", "{\"pin\": \"" + jdbc.sql("SELECT pin FROM ride.rides WHERE id = :id").param("id", ride.id())
                .query(String.class).single() + "\"}");
        drive("complete", "{}");

        List<JsonNode> toRider = riderInbox.await(5);
        assertThat(riderInbox.summary()).containsExactly("ride_status SEARCHING", "ride_status DRIVER_ASSIGNED",
                "ride_status DRIVER_ARRIVED", "ride_status IN_TRIP", "ride_status COMPLETED");
        assertThat(toRider).extracting(message -> message.get("version").asInt()).isSorted()
                .doesNotHaveDuplicates();
        JsonNode assigned = toRider.get(1);
        assertThat(assigned.get("driver").get("first_name").asString()).isEqualTo("Test");
        assertThat(assigned.get("vehicle").has("plate")).isTrue();
        assertThat(assigned.get("promised_pickup_eta_s").asInt()).isPositive();
        assertThat(assigned.has("rider")).isFalse();
        List<JsonNode> toDriver = driverInbox.await(5);
        assertThat(driverInbox.summary()).containsExactly("offer", "ride_status DRIVER_ASSIGNED",
                "ride_status DRIVER_ARRIVED", "ride_status IN_TRIP", "ride_status COMPLETED");
        assertThat(toDriver.get(1).get("rider").get("first_name").asString()).isEqualTo("Aditi");
        assertThat(toDriver.get(1).has("driver")).isFalse();
    }

    @Test
    void aDriverWhoCancelsHearsTheRideGoBackToSearchingAndNothingMore() {
        drive("cancel", "{\"reason\": \"Flat tyre\"}");

        assertThat(riderInbox.await(3).getLast().has("driver")).isFalse();
        assertThat(riderInbox.summary()).containsExactly("ride_status SEARCHING", "ride_status DRIVER_ASSIGNED",
                "ride_status SEARCHING");
        JsonNode released = driverInbox.await(3).getLast();
        assertThat(driverInbox.summary()).containsExactly("offer", "ride_status DRIVER_ASSIGNED",
                "ride_status SEARCHING");
        assertThat(released.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("type", "ride_id", "status", "version");
    }

    @Test
    void aTransitionThatRollsBackPushesNothing() {
        riderInbox.await(2);

        assertThatIllegalStateException().isThrownBy(() -> transactions.run(() -> {
            TestRides.asApi(() -> driverCommands.arrive(driver.id(), ride.id()));
            throw new IllegalStateException("rolled back after the transition");
        }));
        drive("arrive", "{}");

        riderInbox.await(3);
        assertThat(riderInbox.summary()).containsExactly("ride_status SEARCHING", "ride_status DRIVER_ASSIGNED",
                "ride_status DRIVER_ARRIVED");
    }

    private void drive(String action, String body) {
        assertThat(postJson("/v1/rides/" + ride.id() + "/" + action, Map.of("Authorization", driver.authorization(),
                Idempotency.HEADER, UUID.randomUUID().toString()), body).statusCode()).as(action).isEqualTo(200);
    }
}

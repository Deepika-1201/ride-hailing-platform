package com.ridehailing.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.PushBus;
import com.ridehailing.ride.RideView;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.PushInbox;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** LLD §14.7: dispatch pushes the driver their offers, how offers ended without them, and what the server did to them. */
class DriverPushTests extends IntegrationTest {

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestRides rides;

    @Autowired
    private TestUsers users;

    @Autowired
    private PushBus push;

    @Autowired
    private JdbcClient jdbc;

    private TestCity city;
    private GeoPoint pickup;
    private TestDriver driver;
    private PushInbox inbox;

    @BeforeEach
    void setUp() {
        city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        pickup = city.at(0.1, 0.1);
        driver = rides.onlineAt(city, "MINI", city.at(0.1, 0.101));
        inbox = PushInbox.open(push, PushBus.driverChannel(driver.id()));
    }

    @AfterEach
    void closeInbox() {
        inbox.close();
    }

    @Test
    void anOfferIsPushedWithWhatTheDriverNeedsToDecide() {
        RideView ride = rides.book(rides.rider("Aditi").id(), city, pickup, "MINI");

        UUID offerId = offer(ride);

        JsonNode offer = inbox.await(1).getFirst();
        assertThat(offer.get("type").asString()).isEqualTo("offer");
        assertThat(offer.get("offer_id").asString()).isEqualTo(offerId.toString());
        assertThat(offer.get("ride_id").asString()).isEqualTo(ride.id().toString());
        assertThat(offer.get("pickup_distance_m").asInt()).isEqualTo(jdbc.sql(
                "SELECT distance_m FROM dispatch.offers WHERE id = :id").param("id", offerId).query(Integer.class)
                .single());
        assertThat(offer.get("dropoff").get("lat").asDouble()).isEqualTo(ride.dropoff().lat());
        assertThat(offer.get("fare").get("amount_paise").asLong()).isEqualTo(ride.fare().amountPaise());
        assertThat(offer.get("rider").get("first_name").asString()).isEqualTo("Aditi");
        assertThat(offer.get("expires_in_ms").asLong()).as("pushed as the attempt commits")
                .isBetween(14_000L, 15_000L);
    }

    @Test
    void anOfferThatExpiresOrIsWithdrawnIsPushedWithTheReason() {
        UUID expiring = offer(rides.book(rides.rider("Rider").id(), city, pickup, "MINI"));
        rides.fire("OFFER_EXPIRY", expiring);
        TestUser rider = rides.rider("Rider");
        RideView cancelled = rides.book(rider.id(), city, pickup, "MINI");
        UUID withdrawn = offer(cancelled);

        assertThat(postJson("/v1/rides/" + cancelled.id() + "/cancel", Map.of("Authorization", rider.authorization(),
                Idempotency.HEADER, UUID.randomUUID().toString()), "{}").statusCode()).isEqualTo(200);

        assertThat(inbox.await(4)).extracting(message -> message.get("offer_id").asString())
                .containsExactly(expiring.toString(), expiring.toString(), withdrawn.toString(), withdrawn.toString());
        assertThat(inbox.summary()).containsExactly("offer", "offer_withdrawn EXPIRED", "offer",
                "offer_withdrawn RIDER_CANCELLED");
    }

    @Test
    void aSuspensionWithdrawsTheOfferAndTakesTheDriverOffline() {
        offer(rides.book(rides.rider("Rider").id(), city, pickup, "MINI"));

        assertThat(postJson("/v1/ops/drivers/" + driver.id() + "/suspend", Map.of("Authorization",
                users.create(UserRole.OPS).authorization(), Idempotency.HEADER, UUID.randomUUID().toString()),
                "{\"reason\": \"Complaints\"}").statusCode()).isEqualTo(200);

        assertThat(inbox.await(3).getLast().get("version").asLong()).isEqualTo(jdbc.sql(
                "SELECT version FROM dispatch.driver_availability WHERE driver_id = :id").param("id", driver.id())
                .query(Long.class).single());
        assertThat(inbox.summary()).containsExactly("offer", "offer_withdrawn SUSPENDED",
                "driver_status OFFLINE SUSPENDED");
    }

    @Test
    void whatTheDriverDidThemselvesIsntPushed() {
        UUID declined = offer(rides.book(rides.rider("Rider").id(), city, pickup, "MINI"));
        drive("/v1/offers/" + declined + "/decline");
        drive("/v1/drivers/me/offline");
        drive("/v1/drivers/me/online", "{\"vehicle_id\": \"" + driver.vehicleId() + "\"}");
        rides.report(driver, 10, city.at(0.1, 0.101));

        offer(rides.book(rides.rider("Rider").id(), city, pickup, "MINI"));

        assertThat(inbox.await(2)).extracting(message -> message.get("type").asString())
                .containsExactly("offer", "offer");
    }

    private UUID offer(RideView ride) {
        rides.onlyDueIn(city.id());
        rides.search();
        UUID offerId = rides.pendingOffer(ride.id());
        assertThat(offerId).as("an offer for ride %s", ride.id()).isNotNull();
        return offerId;
    }

    private void drive(String path) {
        drive(path, "{}");
    }

    private void drive(String path, String body) {
        assertThat(postJson(path, Map.of("Authorization", driver.authorization(), Idempotency.HEADER,
                UUID.randomUUID().toString()), body).statusCode()).as(path).isEqualTo(200);
    }
}

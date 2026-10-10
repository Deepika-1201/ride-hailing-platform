package com.ridehailing.dispatch;

import static com.ridehailing.support.EventContract.outboxEvents;
import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.MirrorState;
import com.ridehailing.location.LiveIndex.Status;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.ride.RideView;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Ids;
import com.ridehailing.support.EventContract;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestUsers.TestUser;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** An offer from creation to its end (LLD §8.4–§8.7, §7.3, §7.4), through the API where it has one. */
class OfferTests extends IntegrationTest {

    private static final double METRES_PER_DEGREE = 6_371_008.8 * Math.PI / 180;
    private static final String CURRENT_OFFER = "/v1/drivers/me/offer";
    private static final String ACCEPT = "/v1/offers/{offer_id}/accept";
    private static final String DECLINE = "/v1/offers/{offer_id}/decline";
    private static final String RIDE = "/v1/rides/{ride_id}";
    private static final String CANCEL = "/v1/rides/{ride_id}/cancel";

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestRides rides;

    @Autowired
    private DispatchApi dispatch;

    @Autowired
    private LiveIndex index;

    @Autowired
    private JdbcClient jdbc;

    private TestCity city;
    private GeoPoint pickup;
    private TestUser rider;
    private TestDriver driver;
    private RideView ride;
    private UUID offerId;

    @BeforeEach
    void setUp() {
        city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        pickup = city.at(0.1, 0.1);
        rider = rides.rider("Aditi");
        driver = rides.onlineAt(city, "MINI", north(100));
        ride = rides.book(rider.id(), city, pickup, "MINI");
        offerId = offerTo(ride);
    }

    @Test
    void theDriverSeesTheirOfferAndReadingItMarksItSeen() {
        JsonNode offer = assertAnswered("GET", CURRENT_OFFER, call("GET", driver.authorization(), CURRENT_OFFER,
                null), 200);

        assertThat(offer.get("id").asString()).isEqualTo(offerId.toString());
        assertThat(offer.get("status").asString()).isEqualTo("PENDING");
        assertThat(offer.get("pickup_distance_m").asInt()).isEqualTo(100);
        assertThat(offer.get("rider").get("first_name").asString()).isEqualTo("Aditi");
        assertThat(offer.get("expires_in_ms").asLong()).isBetween(1L, 15_000L);
        String seenAt = seenAt();
        assertThat(seenAt).isNotEmpty();
        call("GET", driver.authorization(), CURRENT_OFFER, null);
        assertThat(seenAt()).isEqualTo(seenAt);
        TestDriver other = rides.onlineAt(city, "MINI", north(5_000));
        assertAnswered("GET", CURRENT_OFFER, call("GET", other.authorization(), CURRENT_OFFER, null), 204);
    }

    /** The WebSocket's offer_seen (LLD §14.7) marks an offer as reading it over HTTPS does, and nothing else. */
    @Test
    void offerSeenMarksOnlyTheDriversOwnPendingOffer() {
        dispatch.offerSeen(rides.onlineAt(city, "MINI", north(5_000)).id(), offerId);
        assertThat(seenAt()).as("another driver's").isEmpty();

        rides.fire("OFFER_EXPIRY", offerId);
        dispatch.offerSeen(driver.id(), offerId);

        assertThat(seenAt()).as("ended unseen").isEmpty();
    }

    @Test
    void acceptingAssignsTheDriverAndEndsTheSearch() {
        JsonNode accepted = assertAnswered("POST", ACCEPT, command(driver, "/v1/offers/" + offerId + "/accept"), 200);

        assertThat(accepted.get("status").asString()).isEqualTo("DRIVER_ASSIGNED");
        assertThat(accepted.has("pin")).as("never the driver's").isFalse();
        assertThat(accepted.get("rider").get("first_name").asString()).isEqualTo("Aditi");
        assertThat(accepted.get("promised_pickup_eta_s").asInt()).isPositive();
        JsonNode seenByRider = assertAnswered("GET", RIDE, call("GET", rider.authorization(), "/v1/rides/"
                + ride.id(), null), 200);
        assertThat(seenByRider.get("pin").asString()).matches("[0-9]{4}");
        assertThat(seenByRider.get("driver").get("first_name").asString()).isEqualTo("Test");
        assertThat(seenByRider.get("vehicle").get("id").asString()).isEqualTo(driver.vehicleId().toString());
        assertThat(rides.offerStatus(offerId)).isEqualTo("ACCEPTED");
        assertThat(jdbc.sql("SELECT status || ' ' || ride_id FROM dispatch.driver_availability WHERE driver_id = :id")
                .param("id", driver.id()).query(String.class).single()).isEqualTo("ASSIGNED " + ride.id());
        assertThat(jdbc.sql("SELECT count(*) FROM dispatch.search_tasks WHERE ride_id = :id").param("id", ride.id())
                .query(Long.class).single()).isZero();
        assertThat(rides.timers().exists("OFFER_EXPIRY", offerId)).isFalse();
        assertThat(rides.timers().exists("SEARCH_TIMEOUT", ride.id())).isFalse();
        assertThat(outboxEvents(jdbc, "DriverAssigned", ride.id())).singleElement()
                .satisfies(EventContract::assertConforms);
        assertThat(outboxEvents(jdbc, "OfferAccepted", offerId)).singleElement().satisfies(EventContract::assertConforms);
        assertThat(index.mirrored(city.id())).containsEntry(driver.id(),
                new MirrorState(Status.ASSIGNED, 3, "MINI", ride.id()));
        assertThat(rides.violations(city.id())).isEmpty();

        JsonNode again = assertAnswered("POST", ACCEPT, command(driver, "/v1/offers/" + offerId + "/accept"), 200);
        assertThat(again.get("version").asInt()).isEqualTo(accepted.get("version").asInt());
        assertAnswered("GET", RIDE, call("GET", driver.authorization(), "/v1/rides/" + ride.id(), null), 200);
    }

    @Test
    void someoneElsesOfferOrAnUnknownOneIsNotFound() {
        TestDriver other = rides.onlineAt(city, "MINI", north(5_000));

        assertProblem("POST", ACCEPT, command(other, "/v1/offers/" + offerId + "/accept"), 404, "NOT_FOUND");
        assertProblem("POST", ACCEPT, command(driver, "/v1/offers/" + Ids.newId() + "/accept"), 404, "NOT_FOUND");
        assertProblem("POST", DECLINE, command(other, "/v1/offers/" + offerId + "/decline"), 404, "NOT_FOUND");
    }

    @Test
    void aLateAcceptanceChangesNothing() {
        jdbc.sql("UPDATE dispatch.offers SET expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", offerId).update();

        assertProblem("POST", ACCEPT, command(driver, "/v1/offers/" + offerId + "/accept"), 409,
                "OFFER_NO_LONGER_AVAILABLE");

        assertThat(rides.rideStatus(ride.id())).isEqualTo("SEARCHING");
        assertThat(rides.offerStatus(offerId)).isEqualTo("PENDING");
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void decliningReleasesTheDriverAndTheSearchGoesOn() {
        JsonNode declined = assertAnswered("POST", DECLINE, command(driver, "/v1/offers/" + offerId + "/decline"), 200);

        assertThat(declined.get("status").asString()).isEqualTo("DECLINED");
        assertThat(availability(driver)).isEqualTo("AVAILABLE 0");
        assertThat(taskDue(ride.id())).isTrue();
        assertThat(rides.timers().exists("OFFER_EXPIRY", offerId)).isFalse();
        assertThat(outboxEvents(jdbc, "OfferDeclined", offerId)).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("reason").asString()).isEqualTo("DRIVER");
        });
        assertAnswered("POST", DECLINE, command(driver, "/v1/offers/" + offerId + "/decline"), 200);
        assertProblem("POST", ACCEPT, command(driver, "/v1/offers/" + offerId + "/accept"), 409,
                "OFFER_NO_LONGER_AVAILABLE");
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void goingOfflineDeclinesThePendingOffer() {
        JsonNode status = assertAnswered("POST", "/v1/drivers/me/offline", command(driver, "/v1/drivers/me/offline"),
                200);

        assertThat(status.get("status").asString()).isEqualTo("OFFLINE");
        assertThat(jdbc.sql("SELECT status || ' ' || end_reason FROM dispatch.offers WHERE id = :id")
                .param("id", offerId).query(String.class).single()).isEqualTo("DECLINED DRIVER_OFFLINE");
        assertThat(outboxEvents(jdbc, "OfferDeclined", offerId)).singleElement().satisfies(event ->
                assertThat(event.get("payload").get("reason").asString()).isEqualTo("DRIVER_OFFLINE"));
        assertThat(taskDue(ride.id())).isTrue();
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void aDriverWhoWentOfflineCantAcceptTheirOldOffer() {
        assertAnswered("POST", "/v1/drivers/me/offline", command(driver, "/v1/drivers/me/offline"), 200);

        assertProblem("POST", ACCEPT, command(driver, "/v1/offers/" + offerId + "/accept"), 409,
                "OFFER_NO_LONGER_AVAILABLE");

        assertThat(rides.rideStatus(ride.id())).isEqualTo("SEARCHING");
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void anOfferThatEndedCantBeDeclined() {
        rides.fire("OFFER_EXPIRY", offerId);

        assertProblem("POST", DECLINE, command(driver, "/v1/offers/" + offerId + "/decline"), 409,
                "OFFER_NO_LONGER_AVAILABLE");

        assertThat(rides.offerStatus(offerId)).isEqualTo("EXPIRED");
        assertThat(outboxEvents(jdbc, "OfferDeclined", offerId)).isEmpty();
    }

    /** §8.6: answering an offer, even with a no, shows the driver is there; only expiries in a row count. */
    @Test
    void aDeclineResetsTheExpiriesInARow() {
        call("GET", driver.authorization(), CURRENT_OFFER, null);
        rides.fire("OFFER_EXPIRY", offerId);
        assertThat(availability(driver)).isEqualTo("AVAILABLE 1");
        UUID next = offerTo(rides.book(rides.rider("Rider").id(), city, pickup, "MINI"));

        assertAnswered("POST", DECLINE, command(driver, "/v1/offers/" + next + "/decline"), 200);

        assertThat(availability(driver)).isEqualTo("AVAILABLE 0");
    }

    @Test
    void anUnseenOfferThatExpiresDoesntCountAgainstTheDriver() {
        rides.fire("OFFER_EXPIRY", offerId);

        assertThat(rides.offerStatus(offerId)).isEqualTo("EXPIRED");
        assertThat(availability(driver)).isEqualTo("AVAILABLE 0");
        assertThat(taskDue(ride.id())).isTrue();
        assertThat(outboxEvents(jdbc, "OfferExpired", offerId)).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("seen").asBoolean()).isFalse();
        });
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void threeSeenOffersExpiringInARowTakeTheDriverOffline() {
        UUID offer = offerId;
        for (int expired = 1; expired <= 3; expired++) {
            call("GET", driver.authorization(), CURRENT_OFFER, null);
            rides.fire("OFFER_EXPIRY", offer);
            if (expired < 3) {
                assertThat(availability(driver)).isEqualTo("AVAILABLE " + expired);
                offer = offerTo(rides.book(rides.rider("Rider " + expired).id(), city, pickup, "MINI"));
            }
        }

        assertThat(availability(driver)).isEqualTo("OFFLINE 0");
        assertThat(jdbc.sql("SELECT offline_reason FROM dispatch.driver_sessions WHERE driver_id = :id")
                .param("id", driver.id()).query(String.class).single()).isEqualTo("UNRESPONSIVE");
        assertThat(outboxEvents(jdbc, "DriverWentOffline", driver.id())).singleElement().satisfies(event ->
                assertThat(event.get("payload").get("reason").asString()).isEqualTo("UNRESPONSIVE"));
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void theSearchTimeoutEndsTheRideAndWithdrawsTheOffer() {
        rides.fire("SEARCH_TIMEOUT", ride.id());

        assertThat(rides.rideStatus(ride.id())).isEqualTo("DRIVER_NOT_FOUND");
        assertThat(jdbc.sql("SELECT status || ' ' || end_reason FROM dispatch.offers WHERE id = :id")
                .param("id", offerId).query(String.class).single()).isEqualTo("WITHDRAWN SEARCH_TIMEOUT");
        assertThat(availability(driver)).isEqualTo("AVAILABLE 0");
        assertThat(jdbc.sql("SELECT count(*) FROM dispatch.search_tasks WHERE ride_id = :id").param("id", ride.id())
                .query(Long.class).single()).isZero();
        assertThat(outboxEvents(jdbc, "RideNotMatched", ride.id())).singleElement()
                .satisfies(EventContract::assertConforms);
        assertThat(outboxEvents(jdbc, "OfferWithdrawn", offerId)).singleElement()
                .satisfies(EventContract::assertConforms);
        assertThat(jdbc.sql("""
                        SELECT to_status || ' ' || command || ' ' || actor_type FROM ride.transitions
                        WHERE ride_id = :id ORDER BY version
                        """).param("id", ride.id()).query(String.class).list())
                .containsExactly("SEARCHING BOOK RIDER", "DRIVER_NOT_FOUND SEARCH_TIMEOUT SYSTEM");
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void aTimerOfAnEarlierSearchDoesNothing() {
        jdbc.sql("UPDATE ride.rides SET search_generation = 2 WHERE id = :id").param("id", ride.id()).update();

        rides.fire("SEARCH_TIMEOUT", ride.id());

        assertThat(rides.rideStatus(ride.id())).isEqualTo("SEARCHING");
        assertThat(rides.offerStatus(offerId)).isEqualTo("PENDING");
    }

    @Test
    void cancellingWhileSearchingWithdrawsThePendingOffer() {
        assertAnswered("POST", CANCEL, command(rider, "/v1/rides/" + ride.id() + "/cancel"), 200);

        assertThat(jdbc.sql("SELECT status || ' ' || end_reason FROM dispatch.offers WHERE id = :id")
                .param("id", offerId).query(String.class).single()).isEqualTo("WITHDRAWN RIDER_CANCELLED");
        assertThat(availability(driver)).isEqualTo("AVAILABLE 0");
        assertThat(rides.timers().exists("OFFER_EXPIRY", offerId)).isFalse();
        assertThat(index.mirrored(city.id()).get(driver.id()).status()).isEqualTo(Status.AVAILABLE);
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void withoutALivePositionThePromisedEtaComesFromTheOfferDistance() {
        index.mirror(city.id(), driver.id(), new MirrorState(Status.OFFLINE, 1_000, null, null));

        JsonNode accepted = assertAnswered("POST", ACCEPT, command(driver, "/v1/offers/" + offerId + "/accept"), 200);

        assertThat(accepted.get("promised_pickup_eta_s").asInt()).isEqualTo((int) Math.ceil(100 * 1.35 / 5.0));
    }

    private UUID offerTo(RideView booked) {
        rides.onlyDueIn(city.id());
        rides.search();
        UUID offer = rides.pendingOffer(booked.id());
        assertThat(offer).as("an offer for ride %s", booked.id()).isNotNull();
        return offer;
    }

    private String availability(TestDriver who) {
        return jdbc.sql("SELECT status || ' ' || consecutive_expired FROM dispatch.driver_availability WHERE driver_id = :id")
                .param("id", who.id()).query(String.class).single();
    }

    private boolean taskDue(UUID rideId) {
        return jdbc.sql("SELECT due_at <= now() FROM dispatch.search_tasks WHERE ride_id = :id").param("id", rideId)
                .query(Boolean.class).single();
    }

    /** Empty until the driver has seen the offer. */
    private String seenAt() {
        return jdbc.sql("SELECT coalesce(seen_at::text, '') FROM dispatch.offers WHERE id = :id").param("id", offerId)
                .query(String.class).single();
    }

    private HttpResponse<String> command(TestDriver who, String path) {
        return command(who.authorization(), path);
    }

    private HttpResponse<String> command(TestUser who, String path) {
        return command(who.authorization(), path);
    }

    private HttpResponse<String> command(String authorization, String path) {
        return postJson(path, Map.of("Authorization", authorization, Idempotency.HEADER, UUID.randomUUID().toString()),
                "{}");
    }

    private GeoPoint north(double metres) {
        return new GeoPoint(pickup.lat() + metres / METRES_PER_DEGREE, pickup.lon());
    }
}

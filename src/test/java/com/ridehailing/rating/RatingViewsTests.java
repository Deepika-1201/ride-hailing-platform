package com.ridehailing.rating;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.TestRides.asApi;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.Transactions;
import com.ridehailing.rating.RatingApi.Party;
import com.ridehailing.rating.app.Ratings;
import com.ridehailing.rating.db.RatingRepository;
import com.ridehailing.ride.RideView;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Ids;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestUsers.TestUser;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * LLD §13.4: ratings in views. A ride keeps each party's rating from when it took them; profiles show the current
 * one; a person without ratings has a count of zero and no average.
 */
class RatingViewsTests extends IntegrationTest {

    @Autowired
    TestCities cities;

    @Autowired
    TestPrices prices;

    @Autowired
    TestRides rides;

    @Autowired
    Ratings ratings;

    @Autowired
    RatingRepository repository;

    @Autowired
    Transactions transactions;

    @Test
    void aRideShowsEachPartyTheOthersRatingFromWhenTheRideTookThem() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        GeoPoint pickup = city.at(0.1, 0.1);
        TestUser rider = rides.rider("Asha");
        ratedAs(Party.RIDER, rider.id(), 4, 3);
        TestDriver driver = rides.onlineAt(city, "MINI", pickup);
        ratedAs(Party.DRIVER, driver.id(), 5);

        RideView booked = rides.book(rider.id(), city, pickup, "MINI");
        rides.onlyDueIn(city.id());
        rides.search();
        JsonNode offer = assertAnswered("GET", "/v1/drivers/me/offer", getAs(driver.authorization(),
                "/v1/drivers/me/offer"), 200);
        ratedAs(Party.DRIVER, driver.id(), 1);
        assertAnswered("POST", "/v1/offers/{offer_id}/accept", postJson("/v1/offers/" + offer.get("id").asString()
                + "/accept", Map.of("Authorization", driver.authorization(), Idempotency.HEADER,
                UUID.randomUUID().toString()), "{}"), 200);
        ratedAs(Party.RIDER, rider.id(), 1);
        JsonNode forRider = ride(rider.authorization(), booked.id());
        JsonNode forDriver = ride(driver.authorization(), booked.id());

        assertThat(offer.get("rider").get("first_name").asString()).isEqualTo("Asha");
        assertRating(offer.get("rider").get("rating"), 3.5, 2);
        assertRating(forDriver.get("rider").get("rating"), 3.5, 2);
        assertRating(forRider.get("driver").get("rating"), 3, 2);
    }

    @Test
    void aPersonWithoutRatingsShowsACountOfZeroAndNoAverage() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        TestUser rider = rides.rider("Ravi");
        TestDriver driver = rides.onlineAt(city, "MINI", city.at(0.1, 0.1));

        JsonNode riderProfile = assertAnswered("GET", "/v1/riders/me", getAs(rider.authorization(), "/v1/riders/me"),
                200);
        JsonNode driverProfile = assertAnswered("GET", "/v1/drivers/me", getAs(driver.authorization(),
                "/v1/drivers/me"), 200);

        assertThat(riderProfile.get("rating").toString()).isEqualTo("{\"count\":0}");
        assertThat(driverProfile.get("rating").toString()).isEqualTo("{\"count\":0}");
    }

    @Test
    void profilesShowTheCurrentRating() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        TestUser rider = rides.rider("Meera");
        TestDriver driver = rides.onlineAt(city, "MINI", city.at(0.1, 0.1));
        ratedAs(Party.RIDER, rider.id(), 5, 4);
        ratedAs(Party.DRIVER, driver.id(), 2);

        JsonNode riderProfile = assertAnswered("GET", "/v1/riders/me", getAs(rider.authorization(), "/v1/riders/me"),
                200);
        JsonNode updated = assertAnswered("PATCH", "/v1/riders/me", call("PATCH", rider.authorization(),
                "/v1/riders/me", "{\"last_name\": \"Iyer\"}"), 200);
        JsonNode driverProfile = assertAnswered("GET", "/v1/drivers/me", getAs(driver.authorization(),
                "/v1/drivers/me"), 200);

        assertRating(riderProfile.get("rating"), 4.5, 2);
        assertRating(updated.get("rating"), 4.5, 2);
        assertRating(driverProfile.get("rating"), 2, 1);
    }

    /** Ratings of the person as this party, each by someone else on a ride of its own. */
    private void ratedAs(Party party, UUID person, int... stars) {
        for (int star : stars) {
            UUID rideId = Ids.newId();
            UUID other = Ids.newId();
            UUID riderId = party == Party.RIDER ? person : other;
            UUID driverId = party == Party.RIDER ? other : person;
            transactions.run(() -> repository.openWindow(rideId, riderId, driverId, Instant.now(),
                    Duration.ofDays(7)));
            asApi(() -> ratings.rate(rideId, other, star, null));
        }
    }

    private JsonNode ride(String authorization, UUID rideId) {
        return assertAnswered("GET", "/v1/rides/{ride_id}", getAs(authorization, "/v1/rides/" + rideId), 200);
    }

    private static void assertRating(JsonNode rating, double average, int count) {
        assertThat(rating.get("average").asDouble()).isEqualTo(average);
        assertThat(rating.get("count").asInt()).isEqualTo(count);
    }
}

package com.ridehailing.driver;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.TestRides.asApi;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.Transactions;
import com.ridehailing.rating.app.Ratings;
import com.ridehailing.rating.db.RatingRepository;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestUsers;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** LLD §13.4: admins see each driver's rating, in the list and on the driver. */
class AdminDriverRatingTests extends IntegrationTest {

    @Autowired
    TestCities cities;

    @Autowired
    TestDrivers drivers;

    @Autowired
    TestUsers users;

    @Autowired
    Ratings ratings;

    @Autowired
    RatingRepository repository;

    @Autowired
    Transactions transactions;

    @Test
    void adminsSeeEachDriversRatingAsDriver() {
        TestCity city = cities.create("MINI");
        TestDriver rated = drivers.create(city, "MINI");
        TestDriver unrated = drivers.create(city, "MINI");
        ratedByRiders(rated.id(), 5, 4);
        ratedAsRider(rated.id(), 1);
        ratedAsRider(unrated.id(), 2);
        String admin = users.create(UserRole.ADMIN).authorization();

        JsonNode one = assertAnswered("GET", "/v1/admin/drivers/{driver_id}", getAs(admin,
                "/v1/admin/drivers/" + rated.id()), 200);
        JsonNode list = assertAnswered("GET", "/v1/admin/drivers", getAs(admin, "/v1/admin/drivers?city_id="
                + city.id()), 200);

        assertThat(one.get("rating").get("average").asDouble()).isEqualTo(4.5);
        assertThat(one.get("rating").get("count").asInt()).isEqualTo(2);
        assertThat(list.get("items")).hasSize(2);
        list.get("items").forEach(item -> assertThat(item.get("rating").toString()).isEqualTo(
                item.get("id").asString().equals(unrated.id().toString()) ? "{\"count\":0}"
                        : "{\"average\":4.5,\"count\":2}"));
    }

    /** Ratings of the driver, each by a rider of a ride of its own. */
    private void ratedByRiders(UUID driverId, int... stars) {
        for (int star : stars) {
            UUID rideId = Ids.newId();
            UUID riderId = Ids.newId();
            transactions.run(() -> repository.openWindow(rideId, riderId, driverId, Instant.now(),
                    Duration.ofDays(7)));
            asApi(() -> ratings.rate(rideId, riderId, star, null));
        }
    }

    /** A rating of the same person as a rider, which their driver rating leaves out. */
    private void ratedAsRider(UUID userId, int stars) {
        UUID rideId = Ids.newId();
        UUID driverId = Ids.newId();
        transactions.run(() -> repository.openWindow(rideId, userId, driverId, Instant.now(), Duration.ofDays(7)));
        asApi(() -> ratings.rate(rideId, driverId, stars, null));
    }
}

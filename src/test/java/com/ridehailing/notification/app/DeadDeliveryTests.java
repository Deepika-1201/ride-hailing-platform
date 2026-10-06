package com.ridehailing.notification.app;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.TestRides.asApi;
import static com.ridehailing.support.TestRides.asWorker;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.outbox.EventDelivery;
import com.ridehailing.ride.app.DriverCommands;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestRides.AssignedRide;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** FR-N2: notifications that can't be delivered die in the notification schema and touch nothing else. */
class DeadDeliveryTests extends NotificationTest {

    @Autowired
    TestCities cities;

    @Autowired
    TestPrices prices;

    @Autowired
    TestRides rides;

    @Autowired
    DriverCommands drivers;

    @Autowired
    EventDelivery events;

    @Test
    void aRideWhoseNotificationsAllDieIsUntouched() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        AssignedRide ride = rides.assigned(city, city.at(0.1, 0.1), city.at(0.1, 0.1));
        asApi(() -> {
            drivers.arrive(ride.driver().id(), ride.id());
            drivers.start(ride.driver().id(), ride.id(), ride.pin());
            return drivers.complete(ride.driver().id(), ride.id());
        });
        JsonNode before = rideAsRider(ride);
        asWorker(() -> {
            events.deliverAll(ride.id());
            return null;
        });
        DeliveryExecutor failing = executorWith(push -> {
            throw new IllegalStateException("push service down");
        });

        for (int round = 0; round < 6; round++) {
            assertThat(sendAll(failing)).isEqualTo(5);
            toldAbout(ride.id()).forEach(told -> dueNow(told.deliveryId()));
        }

        assertThat(toldAbout(ride.id())).extracting(Told::kind).containsExactly("DRIVER_ARRIVED",
                "DRIVER_ASSIGNED", "TRIP_COMPLETED", "TRIP_COMPLETED", "TRIP_STARTED");
        assertThat(toldAbout(ride.id())).allSatisfy(told -> assertThat(told.status()).isEqualTo("DEAD"));
        assertThat(rideAsRider(ride)).isEqualTo(before);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM platform.failed_deliveries f JOIN platform.outbox o ON o.event_id = f.event_id
                        WHERE o.partition_key = :ride
                        """).param("ride", ride.id()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM payment.charges WHERE ride_id = :ride").param("ride", ride.id())
                .query(Long.class).single()).as("the fare's charge was still made").isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM rating.rating_windows WHERE ride_id = :ride")
                .param("ride", ride.id()).query(Long.class).single()).as("the rating window still opened")
                .isEqualTo(1);
        assertThat(inboxedBy(ride.id())).as("every consumer took the trip's completion once")
                .containsExactlyInAnyOrder("notification.rides", "payment.charges", "payment.earnings",
                        "rating.windows");
    }

    private JsonNode rideAsRider(AssignedRide ride) {
        return assertAnswered("GET", "/v1/rides/{ride_id}", getAs(ride.rider().authorization(),
                "/v1/rides/" + ride.id()), 200);
    }

    private List<String> inboxedBy(UUID rideId) {
        return jdbc.sql("""
                        SELECT i.consumer FROM platform.inbox i JOIN platform.outbox o ON o.event_id = i.event_id
                        WHERE o.partition_key = :ride AND o.event_type = 'TripCompleted'
                        """)
                .param("ride", rideId)
                .query(String.class)
                .list();
    }
}

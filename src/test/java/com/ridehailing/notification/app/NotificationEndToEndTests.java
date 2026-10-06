package com.ridehailing.notification.app;

import static com.ridehailing.support.TestRides.asApi;
import static com.ridehailing.support.TestRides.asWorker;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.Poller;
import com.ridehailing.platform.outbox.EventDelivery;
import com.ridehailing.ride.RideView;
import com.ridehailing.ride.app.DriverCommands;
import com.ridehailing.rider.app.RiderProfiles;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestRides.AssignedRide;
import com.ridehailing.support.TestUsers.TestUser;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Phase 10's exit criterion (FR-N1): rides taken through every moment a rider or driver is told of, with their events
 * delivered to the consumers and the pushes sent by the worker's poller through the logging provider.
 */
class NotificationEndToEndTests extends NotificationTest {

    private static final Set<String> FR_N1 = Set.of("DRIVER_ASSIGNED", "DRIVER_ARRIVED", "TRIP_STARTED",
            "TRIP_COMPLETED", "PAYMENT_SUCCEEDED", "PAYMENT_FAILED", "RIDE_CANCELLED", "NO_DRIVER_FOUND");

    @Autowired
    TestCities cities;

    @Autowired
    TestPrices prices;

    @Autowired
    TestRides rides;

    @Autowired
    RiderProfiles profiles;

    @Autowired
    DriverCommands drivers;

    @Autowired
    EventDelivery events;

    @Autowired
    ObjectProvider<Poller> pollers;

    @Test
    void everyMomentOfFrN1TellsItsRiderOrDriverAndEveryPushGoesOut() {
        abandonOtherTestsPayments();
        AssignedRide paid = completed(riderWithCard("tok_ok"));
        AssignedRide declined = completed(riderWithCard("tok_decline"));
        AssignedRide cancelled = cancelledByRider();
        UUID unmatched = notMatched();
        assertThat(poll("payment-sender")).as("the two fares charged").isEqualTo(2);
        Stream.of(paid.id(), declined.id(), cancelled.id(), unmatched).forEach(this::deliver);
        double sent = counted(NotificationMetrics.SENT);

        int pushes = poll("notification-deliveries");

        assertThat(told(paid)).containsExactly("DRIVER_ARRIVED to the rider", "DRIVER_ASSIGNED to the rider",
                "PAYMENT_SUCCEEDED to the rider", "TRIP_COMPLETED to the driver", "TRIP_COMPLETED to the rider",
                "TRIP_STARTED to the rider");
        assertThat(told(declined)).containsExactly("DRIVER_ARRIVED to the rider", "DRIVER_ASSIGNED to the rider",
                "PAYMENT_FAILED to the rider", "TRIP_COMPLETED to the driver", "TRIP_COMPLETED to the rider",
                "TRIP_STARTED to the rider");
        assertThat(told(cancelled)).containsExactly("DRIVER_ASSIGNED to the rider", "RIDE_CANCELLED to the driver");
        assertThat(toldAbout(unmatched)).extracting(Told::kind).containsExactly("NO_DRIVER_FOUND");
        List<Told> all = Stream.of(paid.id(), declined.id(), cancelled.id(), unmatched)
                .flatMap(ride -> toldAbout(ride).stream()).toList();
        assertThat(all.stream().map(Told::kind).collect(Collectors.toSet())).isEqualTo(FR_N1);
        assertThat(all).allSatisfy(told -> {
            assertThat(told.status()).isEqualTo("SENT");
            assertThat(told.attempts()).isEqualTo(1);
        });
        assertThat(pushes).isEqualTo(all.size());
        assertThat(counted(NotificationMetrics.SENT) - sent).isEqualTo(all.size());
    }

    /** The executor of the bean poller would otherwise send attempts other tests left open first. */
    private void abandonOtherTestsPayments() {
        for (String table : List.of("payment.charge_attempts", "payment.refunds")) {
            jdbc.sql("UPDATE " + table + """
                     SET status = 'FAILED', failure_code = 'ABANDONED_BY_TEST', lease_until = NULL,
                        next_check_at = NULL, completed_at = now()
                    WHERE status IN ('PENDING', 'IN_FLIGHT', 'UNKNOWN')
                    """).update();
        }
    }

    private TestUser riderWithCard(String token) {
        TestUser rider = rides.rider("Rider");
        UUID card = profiles.addPaymentMethod(rider.id(), "CARD", token, "Visa •• 4242").id();
        profiles.setDefaultPaymentMethod(rider.id(), card);
        return rider;
    }

    /** Each ride takes a city of its own, so its offer can't go to an earlier ride's driver, free again. */
    private TestCity newCity() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        return city;
    }

    private AssignedRide completed(TestUser rider) {
        TestCity city = newCity();
        GeoPoint pickup = city.at(0.1, 0.1);
        AssignedRide ride = rides.assigned(city, pickup, pickup, rider);
        asApi(() -> {
            drivers.arrive(ride.driver().id(), ride.id());
            drivers.start(ride.driver().id(), ride.id(), ride.pin());
            return drivers.complete(ride.driver().id(), ride.id());
        });
        deliver(ride.id());
        return ride;
    }

    /** Within the free window, so no fee is charged. */
    private AssignedRide cancelledByRider() {
        TestCity city = newCity();
        GeoPoint pickup = city.at(0.1, 0.1);
        AssignedRide ride = rides.assigned(city, pickup, pickup);
        HttpResponse<String> cancel = postJson("/v1/rides/" + ride.id() + "/cancel", Map.of("Authorization",
                ride.rider().authorization(), Idempotency.HEADER, UUID.randomUUID().toString()), "{}");
        assertThat(cancel.statusCode()).as(cancel.body()).isEqualTo(200);
        return ride;
    }

    /** Booked where no driver is online; its search times out. */
    private UUID notMatched() {
        TestCity city = newCity();
        RideView ride = rides.book(rides.rider("Rider").id(), city, city.at(0.1, 0.1), "MINI");
        rides.onlyDueIn(city.id());
        rides.search();
        rides.fire("SEARCH_TIMEOUT", ride.id());
        assertThat(rides.rideStatus(ride.id())).isEqualTo("DRIVER_NOT_FOUND");
        return ride.id();
    }

    private void deliver(UUID rideId) {
        asWorker(() -> {
            events.deliverAll(rideId);
            return null;
        });
    }

    /** Polls as a worker node does until nothing is left; answers how many units of work ran. */
    private int poll(String name) {
        Poller poller = pollers.stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow();
        return asWorker(() -> {
            int polled = 0;
            while (poller.poll()) {
                polled++;
            }
            return polled;
        });
    }

    private List<String> told(AssignedRide ride) {
        return toldAbout(ride.id()).stream().map(told -> told.kind() + " to the "
                + (told.recipientId().equals(ride.rider().id()) ? "rider"
                        : told.recipientId().equals(ride.driver().id()) ? "driver" : told.recipientId()))
                .sorted().toList();
    }
}

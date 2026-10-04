package com.ridehailing.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ridehailing.platform.Idempotency;
import com.ridehailing.ride.RideView;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * LLD §17.3: each invariant check reports a broken state, and only in its city. Unique indexes keep most of these
 * states from ever existing: those cases first show that the index refuses the state, then drop the index, break the
 * data and check, all inside a transaction that is rolled back.
 */
class InvariantChecksTests extends IntegrationTest {

    private static final double METRES_PER_DEGREE = 6_371_008.8 * Math.PI / 180;

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestRides rides;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TestCity city;
    private TestCity elsewhere;
    private TestDriver first;
    private TestDriver second;
    private RideView firstRide;
    private RideView secondRide;
    private UUID firstOffer;
    private UUID secondOffer;

    /** Two rides, each with a pending offer to its own driver: the first ride's to the first, nearer driver. */
    @BeforeEach
    void twoRidesWithOffers() {
        city = city();
        elsewhere = city();
        first = rides.onlineAt(city, "MINI", near(city, 100));
        second = rides.onlineAt(city, "MINI", near(city, 300));
        firstRide = rides.book(rides.rider("Rider").id(), city, near(city, 0), "MINI");
        rides.onlyDueIn(city.id());
        rides.search();
        secondRide = rides.book(rides.rider("Rider").id(), city, near(city, 0), "MINI");
        rides.onlyDueIn(city.id());
        rides.search();
        firstOffer = rides.pendingOffer(firstRide.id());
        secondOffer = rides.pendingOffer(secondRide.id());
        assertThat(driverOf(firstOffer)).isEqualTo(first.id());
        assertThat(driverOf(secondOffer)).isEqualTo(second.id());
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void i4FindsAnAssignedDriverWithoutTheirRide() {
        accept(first, firstOffer);

        reported("I4: driver " + first.id() + " is ASSIGNED on ride " + firstRide.id() + ", which they don't drive",
                "UPDATE ride.rides SET status = 'CANCELLED_BY_RIDER', cancelled_by = 'RIDER', ended_at = now() "
                        + "WHERE id = '" + firstRide.id() + "'");
    }

    @Test
    void i4FindsARideWhoseDriverIsntOnIt() {
        accept(first, firstOffer);

        reported("I4: driver " + first.id() + " drives ride " + firstRide.id() + " (DRIVER_ASSIGNED) but is neither "
                + "ASSIGNED nor ON_TRIP", "UPDATE dispatch.driver_availability SET status = 'AVAILABLE', ride_id = NULL "
                + "WHERE driver_id = '" + first.id() + "'");
    }

    @Test
    void i4FindsADriverInTheWrongStateForTheirRide() {
        accept(first, firstOffer);

        reported("I4: driver " + first.id() + " drives ride " + firstRide.id() + " (IN_TRIP) but is ASSIGNED on ride "
                + firstRide.id(), "UPDATE ride.rides SET status = 'IN_TRIP' WHERE id = '" + firstRide.id() + "'");
    }

    @Test
    void i4FindsADriverOnAnotherRide() {
        accept(first, firstOffer);
        UUID other = UUID.randomUUID();

        reported("I4: driver " + first.id() + " drives ride " + firstRide.id() + " (DRIVER_ASSIGNED) but is ASSIGNED "
                + "on ride " + other, "UPDATE dispatch.driver_availability SET ride_id = '" + other
                + "' WHERE driver_id = '" + first.id() + "'");
    }

    @Test
    void i5FindsATransitionOutsideTheTable() {
        reported("I5: ride " + firstRide.id() + " version 0: nothing to SEARCHING by BOOK (DRIVER) is not in the "
                + "transition table", "UPDATE ride.transitions SET actor_type = 'DRIVER' WHERE ride_id = '"
                + firstRide.id() + "' AND version = 0");
    }

    @Test
    void i5FindsALogThatSkipsAState() {
        accept(first, firstOffer);

        reported("I5: ride " + firstRide.id() + " version 1 starts from DRIVER_ASSIGNED after SEARCHING",
                "UPDATE ride.transitions SET from_status = 'DRIVER_ASSIGNED', to_status = 'DRIVER_ARRIVED', "
                        + "command = 'ARRIVE' WHERE ride_id = '" + firstRide.id() + "' AND version = 1");
    }

    @Test
    void i5FindsAGapInTheVersions() {
        accept(first, firstOffer);

        reported("I5: ride " + firstRide.id() + " has version 2 where 1 was expected",
                "UPDATE ride.transitions SET version = 2 WHERE ride_id = '" + firstRide.id() + "' AND version = 1");
    }

    @Test
    void i5FindsARideThatDisagreesWithItsLog() {
        reported("I5: ride " + firstRide.id() + " is DRIVER_NOT_FOUND at version 0 but its log ends at SEARCHING "
                + "version 0", "UPDATE ride.rides SET status = 'DRIVER_NOT_FOUND', ended_at = now() WHERE id = '"
                + firstRide.id() + "'");
    }

    @Test
    void i6FindsAnEndedRideWithAPendingOffer() {
        reported("I6: ride " + firstRide.id() + " is CANCELLED_BY_RIDER but has a pending offer",
                "UPDATE ride.rides SET status = 'CANCELLED_BY_RIDER', cancelled_by = 'RIDER', ended_at = now() "
                        + "WHERE id = '" + firstRide.id() + "'");
    }

    @Test
    void i6FindsAnEndedRideWithASearchTask() {
        reported("I6: ride " + firstRide.id() + " is DRIVER_NOT_FOUND but has a search task",
                "UPDATE dispatch.offers SET status = 'EXPIRED' WHERE id = '" + firstOffer + "'",
                "UPDATE dispatch.driver_availability SET status = 'AVAILABLE', offer_id = NULL WHERE driver_id = '"
                        + first.id() + "'",
                "DELETE FROM platform.timers WHERE aggregate_id = '" + firstOffer + "'",
                "UPDATE ride.rides SET status = 'DRIVER_NOT_FOUND', ended_at = now() WHERE id = '" + firstRide.id()
                        + "'");
    }

    @Test
    void i6FindsAnEndedRideWithAnOfferTimer() {
        reported("I6: ride " + firstRide.id() + " is DRIVER_NOT_FOUND but has an offer timer",
                "UPDATE dispatch.offers SET status = 'EXPIRED' WHERE id = '" + firstOffer + "'",
                "UPDATE dispatch.driver_availability SET status = 'AVAILABLE', offer_id = NULL WHERE driver_id = '"
                        + first.id() + "'",
                "DELETE FROM dispatch.search_tasks WHERE ride_id = '" + firstRide.id() + "'",
                "UPDATE ride.rides SET status = 'DRIVER_NOT_FOUND', ended_at = now() WHERE id = '" + firstRide.id()
                        + "'");
    }

    @Test
    void i6FindsAnEndedRideWithItsSearchTimer() {
        reported("I6: ride " + firstRide.id() + " ended but its search timer is scheduled",
                "UPDATE dispatch.offers SET status = 'EXPIRED' WHERE id = '" + firstOffer + "'",
                "UPDATE dispatch.driver_availability SET status = 'AVAILABLE', offer_id = NULL WHERE driver_id = '"
                        + first.id() + "'",
                "DELETE FROM platform.timers WHERE aggregate_id = '" + firstOffer + "'",
                "DELETE FROM dispatch.search_tasks WHERE ride_id = '" + firstRide.id() + "'",
                "UPDATE ride.rides SET status = 'DRIVER_NOT_FOUND', ended_at = now() WHERE id = '" + firstRide.id()
                        + "'");
    }

    @Test
    void i1FindsARiderInTwoActiveRides() {
        UUID rider = riderOf(firstRide.id());

        refusedThenReported("ride.one_active_ride_per_rider",
                "UPDATE ride.rides SET rider_id = '" + rider + "' WHERE id = '" + secondRide.id() + "'",
                "I1: rider " + rider + " has 2 active rides");
    }

    @Test
    void i1FindsADriverInTwoActiveRides() {
        accept(first, firstOffer);
        accept(second, secondOffer);

        refusedThenReported("ride.one_active_ride_per_driver",
                "UPDATE ride.rides SET driver_id = '" + first.id() + "' WHERE id = '" + secondRide.id() + "'",
                "I1: driver " + first.id() + " has 2 active rides");
    }

    @Test
    void i2FindsADriverWithTwoPendingOffers() {
        refusedThenReported("dispatch.one_pending_offer_per_driver",
                "UPDATE dispatch.offers SET driver_id = '" + first.id() + "' WHERE id = '" + secondOffer + "'",
                "I2: driver " + first.id() + " has 2 pending offers");
    }

    @Test
    void i2FindsARideWithTwoPendingOffers() {
        refusedThenReported("dispatch.one_pending_offer_per_ride",
                "UPDATE dispatch.offers SET ride_id = '" + firstRide.id() + "' WHERE id = '" + secondOffer + "'",
                "I2: ride " + firstRide.id() + " has 2 pending offers");
    }

    @Test
    void i2FindsARideOfferedTwiceToOneDriver() {
        refusedThenReported("dispatch.one_offer_per_ride_and_driver",
                "UPDATE dispatch.offers SET ride_id = '" + firstRide.id() + "', driver_id = '" + first.id()
                        + "', status = 'EXPIRED' WHERE id = '" + secondOffer + "'",
                "I2: ride " + firstRide.id() + " was offered to driver " + first.id() + " 2 times");
    }

    @Test
    void i3FindsAnOfferedDriverWithoutAPendingOffer() {
        reported("I3: driver " + first.id() + " is OFFERED with offer " + firstOffer + ", pending offer none",
                "UPDATE dispatch.offers SET status = 'EXPIRED' WHERE id = '" + firstOffer + "'");
    }

    @Test
    void i3FindsAPendingOfferOnADriverWhoIsntOffered() {
        reported("I3: driver " + first.id() + " is AVAILABLE with offer none, pending offer " + firstOffer, """
                UPDATE dispatch.driver_availability SET status = 'AVAILABLE', offer_id = NULL
                WHERE driver_id = '%s'""".formatted(first.id()));
    }

    @Test
    void i3FindsAnOfferedDriverHoldingAnotherOffer() {
        UUID other = UUID.randomUUID();

        reported("I3: driver " + first.id() + " is OFFERED with offer " + other + ", pending offer " + firstOffer,
                "UPDATE dispatch.driver_availability SET offer_id = '" + other + "' WHERE driver_id = '" + first.id()
                        + "'");
    }

    /** The unique index refuses the broken state by itself; without the index, the check reports it. */
    private void refusedThenReported(String index, String breaking, String expected) {
        assertThatExceptionOfType(DuplicateKeyException.class)
                .isThrownBy(() -> rolledBack(() -> jdbc.sql(breaking).update()));
        reported(expected, "DROP INDEX " + index, breaking);
    }

    /** Runs the statements and reads the violations in one transaction, then rolls it back. */
    private void reported(String expected, String... breaking) {
        rolledBack(() -> {
            for (String sql : breaking) {
                jdbc.sql(sql).update();
            }
            assertThat(rides.violations(city.id())).contains(expected);
            assertThat(rides.violations(elsewhere.id())).isEmpty();
        });
        assertThat(rides.violations(city.id())).isEmpty();
    }

    private void rolledBack(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            transaction.setRollbackOnly();
            work.run();
        });
    }

    private void accept(TestDriver driver, UUID offerId) {
        assertThat(postJson("/v1/offers/" + offerId + "/accept", Map.of("Authorization", driver.authorization(),
                Idempotency.HEADER, UUID.randomUUID().toString()), "{}").statusCode()).isEqualTo(200);
    }

    private UUID driverOf(UUID offerId) {
        return jdbc.sql("SELECT driver_id FROM dispatch.offers WHERE id = :id").param("id", offerId)
                .query(UUID.class).single();
    }

    private UUID riderOf(UUID rideId) {
        return jdbc.sql("SELECT rider_id FROM ride.rides WHERE id = :id").param("id", rideId).query(UUID.class)
                .single();
    }

    private TestCity city() {
        TestCity created = cities.create("MINI");
        prices.price(created.id(), "MINI");
        return created;
    }

    private static GeoPoint near(TestCity city, double metresNorth) {
        GeoPoint pickup = city.at(0.1, 0.1);
        return new GeoPoint(pickup.lat() + metresNorth / METRES_PER_DEGREE, pickup.lon());
    }
}

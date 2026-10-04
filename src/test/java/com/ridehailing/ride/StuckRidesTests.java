package com.ridehailing.ride;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.Idempotency;
import com.ridehailing.ride.app.StuckRides;
import com.ridehailing.ride.db.FlagRepository;
import com.ridehailing.ride.db.FlagRepository.FlagKind;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestRides.AssignedRide;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Ride lifecycle §9: rides in one state too long are flagged and counted (LLD §7.11). */
class StuckRidesTests extends IntegrationTest {

    @Autowired
    private StuckRides stuckRides;

    @Autowired
    private FlagRepository flagRepository;

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestRides rides;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void ridesPastTheirStatesThresholdAreFlaggedOnce() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        UUID searching = rides.book(rides.rider("Rider").id(), city, city.at(0.1, 0.1), "MINI").id();
        UUID searchingRecently = rides.book(rides.rider("Rider").id(), city, city.at(0.15, 0.1), "MINI").id();
        AssignedRide assigned = rides.assigned(city, city.at(0.2, 0.1), city.at(0.2, 0.1));
        age(searching, 4 * 60 + 1);
        age(searchingRecently, 4 * 60 - 30);
        age(assigned.id(), 60 * 60 + 1);
        jdbc.sql("""
                        INSERT INTO ride.flags (id, ride_id, kind, created_at, resolved_at, resolution)
                        VALUES (gen_random_uuid(), :id, 'STUCK', now(), now(), 'handled'),
                               (gen_random_uuid(), :id, 'ARRIVED_FAR', now(), NULL, NULL)
                        """).param("id", searching).update();

        stuckRides.report();

        assertThat(flags(searching)).as("a resolved flag or one of another kind doesn't count")
                .containsExactly("ARRIVED_FAR -", "STUCK SEARCHING");
        assertThat(flags(searchingRecently)).isEmpty();
        assertThat(flags(assigned.id())).containsExactly("STUCK DRIVER_ASSIGNED");
        assertThat(meters.get("rides.stuck").tag("status", "SEARCHING").gauge().value()).isGreaterThanOrEqualTo(1);
        assertThat(meters.get("rides.stuck").tag("status", "IN_TRIP").gauge()).isNotNull();

        stuckRides.report();

        assertThat(flags(searching)).as("one open flag per ride and kind").hasSize(2);
        assertThat(meters.get("rides.stuck").tag("status", "DRIVER_ASSIGNED").gauge().value())
                .as("a flagged ride is still stuck").isGreaterThanOrEqualTo(1);
    }

    @Test
    void aFlagOpenOnTheRideIsntOpenedAgain() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        UUID ride = rides.book(rides.rider("Rider").id(), city, city.at(0.1, 0.1), "MINI").id();

        flagRepository.open(ride, FlagKind.STUCK, "{\"status\": \"SEARCHING\"}");
        flagRepository.open(ride, FlagKind.STUCK, "{\"status\": \"SEARCHING\"}");

        assertThat(flags(ride)).containsExactly("STUCK SEARCHING");
    }

    @Test
    void eachStateIsTimedAgainstItsOwnThreshold() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        UUID assignedRecently = inState(city, 0, RideStatus.DRIVER_ASSIGNED);
        UUID arrived = inState(city, 1, RideStatus.DRIVER_ARRIVED);
        UUID arrivedRecently = inState(city, 2, RideStatus.DRIVER_ARRIVED);
        UUID inTrip = inState(city, 3, RideStatus.IN_TRIP);
        UUID inTripRecently = inState(city, 4, RideStatus.IN_TRIP);
        age(assignedRecently, 60 * 60 - 30);
        age(arrived, 30 * 60 + 1);
        age(arrivedRecently, 30 * 60 - 30);
        age(inTrip, 6 * 60 * 60 + 1);
        age(inTripRecently, 6 * 60 * 60 - 30);

        stuckRides.report();

        assertThat(flags(assignedRecently)).isEmpty();
        assertThat(flags(arrived)).containsExactly("STUCK DRIVER_ARRIVED");
        assertThat(flags(arrivedRecently)).isEmpty();
        assertThat(flags(inTrip)).containsExactly("STUCK IN_TRIP");
        assertThat(flags(inTripRecently)).isEmpty();
    }

    @Test
    void theTimeInAStateCountsFromItsLatestTransition() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        AssignedRide ride = rides.assigned(city, city.at(0.1, 0.1), city.at(0.1, 0.1));
        jdbc.sql("UPDATE ride.transitions SET occurred_at = occurred_at - interval '2 hours' WHERE ride_id = :id "
                + "AND command = 'BOOK'").param("id", ride.id()).update();

        stuckRides.report();

        assertThat(flags(ride.id())).as("assigned just now, booked long ago").isEmpty();
    }

    /** More overdue rides than one query reads: all are flagged in one run, and the gauge counts flagged ones too. */
    @Test
    void oneRunFlagsEveryOverdueRideAndTheGaugeCountsThemAll() {
        String city = TestCities.newId();
        int count = 1_001;
        jdbc.sql("""
                        WITH booked AS (
                            INSERT INTO ride.rides (id, rider_id, city_id, category, quote_id, pickup_lat, pickup_lon,
                                                    dropoff_lat, dropoff_lon, pickup_zone, distance_m, duration_s,
                                                    fare_paise, commission_paise, currency, fee_rule_id,
                                                    payment_method_id, payment_method_type, status, rider_snapshot,
                                                    requested_at)
                            SELECT gen_random_uuid(), gen_random_uuid(), :city, 'MINI', gen_random_uuid(), 0, 0, 0, 0,
                                   'z', 1, 1, 100, 10, 'INR', gen_random_uuid(), gen_random_uuid(), 'CASH',
                                   'SEARCHING', '{}', now() - interval '5 minutes'
                            FROM generate_series(1, :count)
                            RETURNING id)
                        INSERT INTO ride.transitions (id, ride_id, version, to_status, command, actor_type, occurred_at)
                        SELECT gen_random_uuid(), id, 0, 'SEARCHING', 'BOOK', 'RIDER', now() - interval '5 minutes'
                        FROM booked
                        """)
                .param("city", city)
                .param("count", count)
                .update();

        assertThat(stuckRides.report()).isGreaterThanOrEqualTo(count);

        assertThat(stuckFlags(city)).isEqualTo(count);
        assertThat(searchingGauge()).isGreaterThanOrEqualTo(count);
        stuckRides.report();
        assertThat(stuckFlags(city)).as("each is flagged once").isEqualTo(count);
        assertThat(searchingGauge()).as("flagged rides still count").isGreaterThanOrEqualTo(count);
    }

    private long stuckFlags(String city) {
        return jdbc.sql("""
                        SELECT count(*) FROM ride.flags f JOIN ride.rides r ON r.id = f.ride_id
                        WHERE r.city_id = :city AND f.kind = 'STUCK' AND f.resolved_at IS NULL
                        """).param("city", city).query(Long.class).single();
    }

    @Test
    void theGaugesAreExportedForEveryActiveStatus() {
        String scraped = get(managementPort, "/actuator/prometheus").body();

        for (RideStatus status : List.of(RideStatus.SEARCHING, RideStatus.DRIVER_ASSIGNED, RideStatus.DRIVER_ARRIVED,
                RideStatus.IN_TRIP)) {
            assertThat(scraped).contains("rides_stuck{status=\"" + status + "\"}");
        }
        assertThat(scraped).doesNotContain("rides_stuck{status=\"COMPLETED\"}");
        assertThat(scraped).contains("sweeper_safety_valve_total{rule=\"unreachable\"}");
    }

    private double searchingGauge() {
        return meters.get("rides.stuck").tag("status", "SEARCHING").gauge().value();
    }

    /** A ride accepted by a driver at its pickup, the {@code n}th of the city, taken on to the status. */
    private UUID inState(TestCity city, int n, RideStatus status) {
        GeoPoint pickup = city.at(0.05 + 0.07 * n, 0.2);
        AssignedRide ride = rides.assigned(city, pickup, pickup);
        if (status != RideStatus.DRIVER_ASSIGNED) {
            drive(ride, "arrive", "{}");
        }
        if (status == RideStatus.IN_TRIP) {
            drive(ride, "start", "{\"pin\": \"" + ride.pin() + "\"}");
        }
        assertThat(rides.rideStatus(ride.id())).isEqualTo(status.name());
        return ride.id();
    }

    private void drive(AssignedRide ride, String action, String body) {
        assertThat(postJson("/v1/rides/" + ride.id() + "/" + action, Map.of("Authorization",
                ride.driver().authorization(), Idempotency.HEADER, UUID.randomUUID().toString()), body).statusCode())
                .isEqualTo(200);
    }

    /** Moves the ride's latest transition back, as if it had been in its state that long. */
    private void age(UUID rideId, int seconds) {
        jdbc.sql("""
                        UPDATE ride.transitions SET occurred_at = occurred_at - make_interval(secs => :seconds)
                        WHERE ride_id = :id AND version = (SELECT max(version) FROM ride.transitions WHERE ride_id = :id)
                        """)
                .param("seconds", seconds)
                .param("id", rideId)
                .update();
    }

    /** The ride's open flags by kind, each with the status it records, or {@code -}. */
    private List<String> flags(UUID rideId) {
        return jdbc.sql("""
                        SELECT kind || ' ' || coalesce(details ->> 'status', '-') FROM ride.flags
                        WHERE ride_id = :id AND resolved_at IS NULL ORDER BY kind
                        """)
                .param("id", rideId).query(String.class).list();
    }
}

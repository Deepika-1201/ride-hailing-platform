package com.ridehailing.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.dispatch.db.AvailabilityRepository;
import com.ridehailing.dispatch.db.DecisionRepository;
import com.ridehailing.dispatch.db.DriverStatsRepository;
import com.ridehailing.dispatch.db.OfferRepository;
import com.ridehailing.dispatch.db.SearchTaskRepository;
import com.ridehailing.geography.GeographyApi;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.Timers;
import com.ridehailing.platform.Transactions;
import com.ridehailing.ride.RideAssignment;
import com.ridehailing.ride.RideView;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Dispatch §12: while the live index fails, a search backs off 1, 2, 4, 8, then 10 seconds, and recovers after. */
class IndexOutageTests extends IntegrationTest {

    @Autowired
    private SearchTaskRepository tasks;

    @Autowired
    private RideAssignment assignment;

    @Autowired
    private OfferRepository offers;

    @Autowired
    private AvailabilityRepository availability;

    @Autowired
    private DecisionRepository decisions;

    @Autowired
    private DriverStatsRepository stats;

    @Autowired
    private GeographyApi geography;

    @Autowired
    private LiveIndex index;

    @Autowired
    private Rankers rankers;

    @Autowired
    private Timers timers;

    @Autowired
    private Outbox outbox;

    @Autowired
    private LiveIndexMirror mirror;

    @Autowired
    private Transactions transactions;

    @Autowired
    private DispatchProperties properties;

    @Autowired
    private DispatchMetrics metrics;

    @Autowired
    private JsonMapper json;

    @Autowired
    private Clock clock;

    @Autowired
    private SearchAttempts working;

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestRides rides;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void aSearchBacksOffWhileTheIndexFailsThenOffers() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        rides.onlineAt(city, "MINI", city.at(0.1, 0.1));
        RideView ride = rides.book(rides.rider("Rider").id(), city, city.at(0.1, 0.1), "MINI");
        SearchAttempts failing = attemptsWith(failingNearby(index));

        for (int backoffS : List.of(1, 2, 4, 8, 10, 10)) {
            rides.onlyDueIn(city.id());
            assertThat(TestRides.asDispatch(failing::runNext)).isTrue();

            Map<String, Object> task = jdbc.sql("""
                            SELECT backoff_s, extract(epoch FROM due_at - clock_timestamp()) AS due_in_s
                            FROM dispatch.search_tasks WHERE ride_id = :ride
                            """)
                    .param("ride", ride.id()).query().singleRow();
            assertThat(task.get("backoff_s")).isEqualTo(backoffS);
            assertThat(((Number) task.get("due_in_s")).doubleValue()).isBetween(backoffS - 1.0, backoffS + 0.0);
            makeDue(ride.id());
        }
        assertThat(jdbc.sql("""
                        SELECT outcome FROM dispatch.decisions WHERE ride_id = :ride ORDER BY attempt
                        """).param("ride", ride.id()).query(String.class).list())
                .containsExactly("INDEX_UNAVAILABLE", "INDEX_UNAVAILABLE", "INDEX_UNAVAILABLE", "INDEX_UNAVAILABLE",
                        "INDEX_UNAVAILABLE", "INDEX_UNAVAILABLE");

        rides.onlyDueIn(city.id());
        assertThat(TestRides.asDispatch(working::runNext)).isTrue();

        assertThat(rides.pendingOffer(ride.id())).isNotNull();
        assertThat(jdbc.sql("SELECT backoff_s FROM dispatch.search_tasks WHERE ride_id = :ride")
                .param("ride", ride.id()).query(Integer.class).single()).isZero();
        assertThat(rides.violations(city.id())).isEmpty();
    }

    private SearchAttempts attemptsWith(LiveIndex live) {
        return new SearchAttempts(tasks, assignment, offers, availability, decisions, stats, geography, live, rankers,
                timers, outbox, mirror, transactions, properties, metrics, json, clock);
    }

    private void makeDue(UUID rideId) {
        jdbc.sql("UPDATE dispatch.search_tasks SET due_at = now() WHERE ride_id = :ride").param("ride", rideId)
                .update();
    }

    /** The real index, except that searching it fails. */
    private static LiveIndex failingNearby(LiveIndex real) {
        return (LiveIndex) Proxy.newProxyInstance(LiveIndex.class.getClassLoader(), new Class<?>[] {LiveIndex.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("nearby")) {
                        throw new IllegalStateException("The index is down");
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}

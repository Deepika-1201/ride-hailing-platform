package com.ridehailing.dispatch.app;

import static com.ridehailing.support.EventContract.outboxEvents;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.dispatch.AvailabilityStatus;
import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.dispatch.db.AvailabilityRepository;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.Candidate;
import com.ridehailing.location.LiveIndex.LocationUpdate;
import com.ridehailing.location.LiveIndex.Status;
import com.ridehailing.location.LocationProperties;
import com.ridehailing.platform.LogContext;
import com.ridehailing.platform.Transactions;
import com.ridehailing.shared.Ids;
import com.ridehailing.support.EventContract;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers;
import com.ridehailing.support.TestDrivers.TestDriver;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * LLD §8.9 against the database: silent drivers leave matching after 30 s and go offline after 10 min, unless the
 * safety valve holds. The sweep runs 11 minutes ahead; a scripted index supplies what the in-memory one can't.
 */
class SweeperTests extends IntegrationTest {

    @Autowired
    private AvailabilityRepository availability;

    @Autowired
    private Availability service;

    @Autowired
    private LiveIndex realIndex;

    @Autowired
    private LocationProperties location;

    @Autowired
    private Transactions transactions;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private TestCities cities;

    @Autowired
    private TestDrivers drivers;

    @Autowired
    private DispatchApi dispatch;

    @Autowired
    private JdbcClient jdbc;

    private ScriptedLiveIndex index;
    private Sweeper sweeper;
    private TestCity city;
    private Instant later;

    @BeforeEach
    void setUp() {
        index = new ScriptedLiveIndex(realIndex);
        sweeper = new Sweeper(availability, service, index, location, transactions, Clock.systemUTC(), meters);
        city = cities.create("MINI");
        later = Instant.now().plus(Duration.ofMinutes(11));
    }

    @Test
    void aDriverSilentForThirtySecondsLeavesMatchingButStaysOnline() {
        UUID driver = online(city);
        realIndex.update(city.id(), driver, "MINI", new LocationUpdate(1, city.at(0.1, 0.1), 5, null, null,
                Instant.now()));
        double removed = meters.counter("location.sweeper.removed").count();

        assertThat(sweep(Instant.now().plusSeconds(31))).isEmpty();

        assertThat(realIndex.nearby(city.id(), "MINI", city.at(0.1, 0.1), 1_000, 5)).extracting(Candidate::driverId)
                .doesNotContain(driver);
        assertThat(meters.counter("location.sweeper.removed").count()).isEqualTo(removed + 1);
        assertThat(dispatch.status(driver).status()).isEqualTo(AvailabilityStatus.AVAILABLE);
    }

    @Test
    void aDriverSilentForTenMinutesGoesOfflineAndOneHeardFromStays() {
        UUID quiet = online(city);
        UUID heard = online(city);
        index.seen.put(heard, later.minus(Duration.ofMinutes(5)));

        assertThat(sweep(later)).containsExactly(quiet);

        assertThat(dispatch.status(quiet).status()).isEqualTo(AvailabilityStatus.OFFLINE);
        assertThat(dispatch.status(heard).status()).isEqualTo(AvailabilityStatus.AVAILABLE);
        assertThat(jdbc.sql("SELECT offline_reason FROM dispatch.driver_sessions WHERE driver_id = :driverId")
                .param("driverId", quiet).query(String.class).single()).isEqualTo("SILENT");
        assertThat(outboxEvents(jdbc, "DriverWentOffline", quiet)).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("reason").asString()).isEqualTo("SILENT");
        });
        assertThat(jdbc.sql("""
                        SELECT actor_type || ' ' || actor_id || ' ' || reason FROM audit.audit_log
                        WHERE entity_type = 'availability' AND entity_id = :id AND action = 'availability.offline'
                        """).param("id", quiet.toString()).query(String.class).single())
                .isEqualTo("SYSTEM sweeper SILENT");
        assertThat(realIndex.mirrored(city.id()).get(quiet).status()).isEqualTo(Status.OFFLINE);
    }

    @Test
    void tenMinutesOfSilenceIsNotYetIdle() {
        UUID exactly = online(city);
        UUID longer = online(city);
        index.seen.put(exactly, later.minus(location.offlineAfter()));
        index.seen.put(longer, later.minus(location.offlineAfter()).minusMillis(1));

        assertThat(sweep(later)).containsExactly(longer);
    }

    @Test
    void fiveSilentDriversAtOnceGoOfflineAndADriverWithAnOfferDoesntCount() {
        List<UUID> silent = onlineDrivers(5);
        UUID offered = online(city);
        jdbc.sql("""
                        UPDATE dispatch.driver_availability SET status = 'OFFERED', offer_id = :offerId
                        WHERE driver_id = :driverId
                        """).param("offerId", Ids.newId()).param("driverId", offered).update();

        assertThat(sweep(later)).containsExactlyInAnyOrderElementsOf(silent);
        assertThat(dispatch.status(offered).status()).isEqualTo(AvailabilityStatus.OFFERED);
    }

    @Test
    void sixSilentDriversAtOnceTripTheSafetyValve() {
        List<UUID> silent = onlineDrivers(6);
        double trips = valveTrips();

        assertThat(sweep(later)).isEmpty();

        assertThat(valveTrips()).isEqualTo(trips + 1);
        assertThat(silent).allMatch(driver -> dispatch.status(driver).status() == AvailabilityStatus.AVAILABLE);
    }

    @Test
    void anIndexRestartedWithinTheRulesThresholdTripsTheSafetyValve() {
        UUID driver = online(city);
        index.epochs.put(city.id(), later.minus(Duration.ofMinutes(5)));
        index.seen.put(driver, later.minus(Duration.ofMinutes(11)));
        double trips = valveTrips();

        assertThat(sweep(later)).isEmpty();

        assertThat(valveTrips()).isEqualTo(trips + 1);
        assertThat(dispatch.status(driver).status()).isEqualTo(AvailabilityStatus.AVAILABLE);
    }

    @Test
    void aDriverHeardFromBeforeTheLockStaysOnline() {
        UUID driver = online(city);
        index.laterSeen = id -> later.minus(Duration.ofMinutes(1));

        assertThat(sweep(later)).isEmpty();

        assertThat(dispatch.status(driver).status()).isEqualTo(AvailabilityStatus.AVAILABLE);
    }

    @Test
    void aDriverWhoseStatusChangedBeforeTheLockIsLeftAlone() {
        UUID driver = online(city);
        index.onFirstLastSeen = () -> jdbc.sql("""
                        UPDATE dispatch.driver_availability
                        SET status = 'OFFERED', offer_id = :offerId, version = version + 1
                        WHERE driver_id = :driverId
                        """).param("offerId", Ids.newId()).param("driverId", driver).update();

        assertThat(sweep(later)).isEmpty();

        assertThat(dispatch.status(driver).status()).isEqualTo(AvailabilityStatus.OFFERED);
        assertThat(outboxEvents(jdbc, "DriverWentOffline", driver)).isEmpty();
    }

    @Test
    void oneCitysFailureDoesntStopTheOthers() {
        TestCity other = cities.create("MINI");
        online(city);
        online(other);
        index.failingSweeps.add("*");

        LogContext.run(Map.of(LogContext.ROLE, "dispatch"), sweeper::sweepAll);

        assertThat(index.sweptCities).contains(city.id(), other.id());
    }

    private UUID online(TestCity where) {
        TestDriver driver = drivers.create(where, "MINI");
        LogContext.run(Map.of(LogContext.ROLE, "api"), () -> dispatch.goOnline(driver.id(), driver.vehicleId()));
        return driver.id();
    }

    private List<UUID> onlineDrivers(int count) {
        List<UUID> online = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            online.add(online(city));
        }
        return online;
    }

    private List<UUID> sweep(Instant now) {
        return inDispatch(() -> sweeper.sweep(city.id(), now));
    }

    private double valveTrips() {
        return meters.counter("sweeper.safety.valve", "rule", "idle").count();
    }

    private static <T> T inDispatch(Supplier<T> work) {
        List<T> result = new ArrayList<>(1);
        LogContext.run(Map.of(LogContext.ROLE, "dispatch"), () -> result.add(work.get()));
        return result.getFirst();
    }
}

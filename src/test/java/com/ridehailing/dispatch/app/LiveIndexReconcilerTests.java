package com.ridehailing.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.dispatch.db.AvailabilityRepository;
import com.ridehailing.location.LiveIndex.MirrorState;
import com.ridehailing.location.LiveIndex.Status;
import com.ridehailing.location.LocationProperties;
import com.ridehailing.location.index.InMemoryLiveIndex;
import com.ridehailing.platform.LogContext;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.MutableClock;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers;
import com.ridehailing.support.TestDrivers.TestDriver;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** LLD §8.10: the reconciler makes an index that missed writes, or lost everything, agree with PostgreSQL. */
class LiveIndexReconcilerTests extends IntegrationTest {

    @Autowired
    private AvailabilityRepository availability;

    @Autowired
    private LocationProperties location;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private TestCities cities;

    @Autowired
    private TestDrivers drivers;

    @Autowired
    private DispatchApi dispatch;

    private InMemoryLiveIndex index;
    private LiveIndexReconciler reconciler;
    private TestCity city;

    @BeforeEach
    void setUp() {
        index = new InMemoryLiveIndex(Clock.systemUTC(), Duration.ofSeconds(30), Duration.ofMinutes(10));
        reconciler = new LiveIndexReconciler(availability, index, new LiveIndexMirror(index, meters), location,
                Clock.systemUTC());
        city = cities.create("MINI");
    }

    @Test
    void anIndexThatLostItsDataGetsEveryOnlineDriverBack() {
        UUID online = online(city);
        UUID offline = online(city);
        inApi(() -> dispatch.goOffline(offline));

        assertThat(reconciler.reconcile(city.id())).isEqualTo(1);

        assertThat(index.mirrored(city.id())).isEqualTo(Map.of(online, new MirrorState(Status.AVAILABLE, 1, "MINI",
                null)));
        assertThat(reconciler.reconcile(city.id())).as("nothing left to repair").isZero();
    }

    @Test
    void aMissedOfflineWriteIsRepaired() {
        UUID driver = online(city);
        index.mirror(city.id(), driver, new MirrorState(Status.AVAILABLE, 1, "MINI", null));
        inApi(() -> dispatch.goOffline(driver));

        assertThat(reconciler.reconcile(city.id())).isEqualTo(1);

        assertThat(index.mirrored(city.id())).containsEntry(driver, new MirrorState(Status.OFFLINE, 2, null, null));
    }

    @Test
    void aNewerEntryThanTheRowIsLeftAlone() {
        UUID driver = online(city);
        index.mirror(city.id(), driver, new MirrorState(Status.OFFERED, 5, "MINI", null));

        assertThat(reconciler.reconcile(city.id())).isZero();

        assertThat(index.mirrored(city.id())).containsEntry(driver, new MirrorState(Status.OFFERED, 5, "MINI", null));
    }

    @Test
    void aDriverOnlineInAnotherCityIsOfflineInThisOne() {
        TestCity other = cities.create("MINI");
        UUID driver = online(other);
        index.mirror(city.id(), driver, new MirrorState(Status.AVAILABLE, 0, "MINI", null));

        assertThat(reconciler.reconcile(city.id())).isEqualTo(1);

        assertThat(index.mirrored(city.id())).containsEntry(driver, new MirrorState(Status.OFFLINE, 1, null, null));
    }

    @Test
    void everyCityWithOnlineDriversOrARecentChangeIsReconciled() {
        UUID online = online(city);
        TestCity quiet = cities.create("MINI");
        UUID gone = online(quiet);
        index.mirror(quiet.id(), gone, new MirrorState(Status.AVAILABLE, 1, "MINI", null));
        inApi(() -> dispatch.goOffline(gone));

        reconciler.reconcileAll();

        assertThat(index.mirrored(city.id())).containsKey(online);
        assertThat(index.mirrored(quiet.id())).containsEntry(gone, new MirrorState(Status.OFFLINE, 2, null, null));
        assertThat(index.beginEpoch(city.id())).as("the run began the epoch").isFalse();
    }

    @Test
    void theWatchReconcilesACityAtOnceOnlyWhenItsEpochWasMissing() {
        UUID driver = online(city);

        reconciler.watch();
        assertThat(index.mirrored(city.id())).containsEntry(driver, new MirrorState(Status.AVAILABLE, 1, "MINI", null));

        inApi(() -> dispatch.goOffline(driver));
        reconciler.watch();
        assertThat(index.mirrored(city.id())).as("missed writes wait for the full run")
                .containsEntry(driver, new MirrorState(Status.AVAILABLE, 1, "MINI", null));

        reconciler.reconcileAll();
        assertThat(index.mirrored(city.id())).containsEntry(driver, new MirrorState(Status.OFFLINE, 2, null, null));
    }

    @Test
    void theWatchRereadsItsCitiesEveryThirtySeconds() {
        MutableClock clock = new MutableClock(Instant.now());
        LiveIndexReconciler watching = new LiveIndexReconciler(availability, index,
                new LiveIndexMirror(index, meters), location, clock);
        watching.watch();
        TestCity later = cities.create("MINI");
        UUID driver = online(later);

        clock.advance(LiveIndexReconciler.CITIES_FOR.minusMillis(1));
        watching.watch();
        assertThat(index.mirrored(later.id())).isEmpty();

        clock.advance(Duration.ofMillis(1));
        watching.watch();
        assertThat(index.mirrored(later.id())).containsKey(driver);
    }

    private UUID online(TestCity where) {
        TestDriver driver = drivers.create(where, "MINI");
        inApi(() -> dispatch.goOnline(driver.id(), driver.vehicleId()));
        return driver.id();
    }

    private static void inApi(Runnable work) {
        LogContext.run(Map.of(LogContext.ROLE, "api"), work);
    }
}

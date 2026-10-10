package com.ridehailing.location;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.location.LiveIndex.Candidate;
import com.ridehailing.location.LiveIndex.LivePosition;
import com.ridehailing.location.LiveIndex.LocationUpdate;
import com.ridehailing.location.LiveIndex.MirrorState;
import com.ridehailing.location.LiveIndex.OnlineDriver;
import com.ridehailing.location.LiveIndex.Status;
import com.ridehailing.location.LiveIndex.UpdateResult;
import com.ridehailing.location.LiveIndex.UpdateResult.Outcome;
import com.ridehailing.shared.BoundingBox;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Ids;
import com.ridehailing.support.MutableClock;
import com.ridehailing.support.TestCities;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What every live index must do (LLD §9.1–§9.4), whatever stores it: the in-memory index of V1 and the Valkey one of
 * V2 run the same suite. Time comes from a clock the suite moves.
 */
public abstract class LiveIndexContract {

    protected static final Duration FRESHNESS = Duration.ofSeconds(30);
    protected static final Duration TOMBSTONE_TTL = Duration.ofMinutes(10);
    protected static final String MINI = "MINI";
    protected static final String SEDAN = "SEDAN";
    protected static final GeoPoint HERE = new GeoPoint(12.9716, 77.5946);
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    protected final MutableClock clock = new MutableClock(Instant.parse("2026-10-02T08:00:00Z"));
    protected LiveIndex index;
    protected String city;

    protected abstract LiveIndex newIndex(Clock clock, Duration freshness, Duration tombstoneTtl);

    @BeforeEach
    void createIndex() {
        index = newIndex(clock, FRESHNESS, TOMBSTONE_TTL);
        city = TestCities.newId();
    }

    @Test
    void anUpdateFromADriverTheIndexDoesntHoldIsOffline() {
        UUID stranger = Ids.newId();

        UpdateResult result = send(stranger, 1, HERE);

        assertThat(result.outcome()).isEqualTo(Outcome.OFFLINE);
        assertThat(result.status()).isEqualTo(Status.OFFLINE);
        assertThat(index.position(city, stranger)).isEmpty();
        assertThat(index.lastSeen(city, List.of(stranger))).isEmpty();
        assertThat(index.nearby(city, MINI, HERE, 1_000, 5)).isEmpty();
    }

    @Test
    void anAvailableDriverIsACandidateFromTheirFirstUpdate() {
        UUID driver = available(MINI, 1);
        assertThat(index.nearby(city, MINI, HERE, 1_000, 5)).as("no position yet").isEmpty();

        UpdateResult result = send(driver, 1, north(100));

        assertThat(result).isEqualTo(new UpdateResult(Outcome.APPLIED, Status.AVAILABLE, null, null));
        assertThat(index.nearby(city, MINI, HERE, 1_000, 5))
                .containsExactly(new Candidate(driver, north(100), 100, clock.instant()));
        assertThat(index.position(city, driver)).contains(new LivePosition(north(100), clock.instant(), 1));
        assertThat(index.lastSeen(city, List.of(driver))).isEqualTo(Map.of(driver, clock.instant()));
    }

    @Test
    void repeatedAndOlderSequenceNumbersAreStaleAndChangeNothing() {
        UUID driver = available(MINI, 1);
        send(driver, 5, north(100));
        Instant applied = clock.instant();
        clock.advance(Duration.ofSeconds(10));

        UpdateResult duplicate = send(driver, 5, north(200));
        UpdateResult older = send(driver, 4, north(300));

        assertThat(List.of(duplicate, older)).containsOnly(new UpdateResult(Outcome.STALE, Status.AVAILABLE, null,
                null));
        assertThat(index.position(city, driver)).contains(new LivePosition(north(100), applied, 5));
        assertThat(index.lastSeen(city, List.of(driver))).isEqualTo(Map.of(driver, applied));
        assertThat(send(driver, 6, north(400)).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(index.position(city, driver)).contains(new LivePosition(north(400), clock.instant(), 6));
    }

    @Test
    void anUpdateInAnotherCategoryAnswersTheMirrorsCategoryAndIsNotApplied() {
        UUID driver = available(MINI, 1);

        UpdateResult result = index.update(city, driver, SEDAN, update(1, north(100)));

        assertThat(result).isEqualTo(new UpdateResult(Outcome.CATEGORY_MISMATCH, Status.AVAILABLE, MINI, null));
        assertThat(index.position(city, driver)).isEmpty();
        assertThat(index.nearby(city, SEDAN, HERE, 1_000, 5)).isEmpty();
    }

    @Test
    void mirrorWritesNotNewerThanTheIndexsAreIgnored() {
        UUID driver = Ids.newId();

        assertThat(index.mirror(city, driver, new MirrorState(Status.AVAILABLE, 2, MINI, null))).isTrue();
        assertThat(index.mirror(city, driver, new MirrorState(Status.OFFLINE, 1, null, null))).isFalse();
        assertThat(index.mirror(city, driver, new MirrorState(Status.OFFERED, 2, MINI, null))).isFalse();
        assertThat(index.mirrored(city)).containsEntry(driver, new MirrorState(Status.AVAILABLE, 2, MINI, null));

        assertThat(index.mirror(city, driver, new MirrorState(Status.OFFERED, 3, MINI, null))).isTrue();
        assertThat(index.mirrored(city)).containsEntry(driver, new MirrorState(Status.OFFERED, 3, MINI, null));
    }

    @Test
    void aDriverWithAnOfferLeavesMatchingButKeepsUpdating() {
        UUID driver = available(MINI, 1);
        send(driver, 1, north(100));

        index.mirror(city, driver, new MirrorState(Status.OFFERED, 2, MINI, null));
        assertThat(index.nearby(city, MINI, HERE, 1_000, 5)).isEmpty();
        clock.advance(Duration.ofSeconds(2));
        assertThat(send(driver, 2, north(150))).isEqualTo(new UpdateResult(Outcome.APPLIED, Status.OFFERED, null,
                null));
        assertThat(index.nearby(city, MINI, HERE, 1_000, 5)).isEmpty();

        index.mirror(city, driver, new MirrorState(Status.AVAILABLE, 3, MINI, null));
        assertThat(index.nearby(city, MINI, HERE, 1_000, 5))
                .containsExactly(new Candidate(driver, north(150), 150, clock.instant()));
    }

    @Test
    void updatesAnswerWithTheMirrorsRide() {
        UUID driver = available(MINI, 1);
        UUID ride = Ids.newId();
        index.mirror(city, driver, new MirrorState(Status.ASSIGNED, 2, MINI, ride));

        assertThat(send(driver, 1, HERE)).isEqualTo(new UpdateResult(Outcome.APPLIED, Status.ASSIGNED, null, ride));
        assertThat(index.nearby(city, MINI, HERE, 1_000, 5)).isEmpty();
        assertThat(index.position(city, driver)).isPresent();
    }

    @Test
    void goingOfflineForgetsThePositionAndTheSequence() {
        UUID driver = available(MINI, 1);
        send(driver, 100, north(100));

        assertThat(index.mirror(city, driver, new MirrorState(Status.OFFLINE, 2, null, null))).isTrue();

        assertThat(send(driver, 101, north(100)).outcome()).isEqualTo(Outcome.OFFLINE);
        assertThat(index.position(city, driver)).isEmpty();
        assertThat(index.lastSeen(city, List.of(driver))).isEmpty();
        index.mirror(city, driver, new MirrorState(Status.AVAILABLE, 3, MINI, null));
        assertThat(index.nearby(city, MINI, HERE, 1_000, 5)).as("no position since going online").isEmpty();
        assertThat(send(driver, 1, north(50)).outcome()).as("a new device starts again at 1")
                .isEqualTo(Outcome.APPLIED);
        assertThat(index.nearby(city, MINI, HERE, 1_000, 5)).extracting(Candidate::driverId).containsExactly(driver);
    }

    @Test
    void anOfflineTombstoneKeepsOlderWritesOutUntilItExpires() {
        UUID driver = Ids.newId();
        index.mirror(city, driver, new MirrorState(Status.OFFLINE, 5, null, null));

        assertThat(index.mirror(city, driver, new MirrorState(Status.AVAILABLE, 4, MINI, null))).isFalse();
        clock.advance(TOMBSTONE_TTL.minusMillis(1));
        assertThat(index.mirrored(city)).containsEntry(driver, new MirrorState(Status.OFFLINE, 5, null, null));

        clock.advance(Duration.ofMillis(1));
        assertThat(index.mirrored(city)).doesNotContainKey(driver);
        assertThat(index.mirror(city, driver, new MirrorState(Status.AVAILABLE, 4, MINI, null))).isTrue();
    }

    @Test
    void onlyDriversHeardFromWithinTheFreshnessWindowAreCandidates() {
        UUID driver = available(MINI, 1);
        send(driver, 1, north(100));

        clock.advance(FRESHNESS);
        assertThat(index.nearby(city, MINI, HERE, 1_000, 5)).hasSize(1);
        clock.advance(Duration.ofMillis(1));
        assertThat(index.nearby(city, MINI, HERE, 1_000, 5)).isEmpty();

        send(driver, 2, north(100));
        assertThat(index.nearby(city, MINI, HERE, 1_000, 5)).hasSize(1);
    }

    @Test
    void candidatesAreTheNearestOfTheCategoryWithinTheRadiusUpToK() {
        UUID far = at(north(300));
        UUID nearest = at(north(100));
        UUID middle = at(north(200));
        UUID outside = at(north(1_500));
        UUID sedan = Ids.newId();
        index.mirror(city, sedan, new MirrorState(Status.AVAILABLE, 1, SEDAN, null));
        index.update(city, sedan, SEDAN, update(1, north(50)));

        assertThat(index.nearby(city, MINI, HERE, 1_000, 2)).extracting(Candidate::driverId, Candidate::distanceM)
                .containsExactly(tuple(nearest, 100), tuple(middle, 200));
        assertThat(index.nearby(city, MINI, HERE, 1_000, 10)).extracting(Candidate::driverId)
                .containsExactly(nearest, middle, far);
        assertThat(index.nearby(city, MINI, HERE, 2_000, 10)).extracting(Candidate::driverId)
                .containsExactly(nearest, middle, far, outside);
        assertThat(index.nearby(city, SEDAN, HERE, 2_000, 10)).extracting(Candidate::driverId).containsExactly(sedan);
    }

    @Test
    void theNearestComeFirstAmongManyCandidates() {
        List<UUID> drivers = new ArrayList<>();
        for (int metres = 600; metres >= 100; metres -= 100) {
            drivers.addFirst(at(north(metres)));
        }

        assertThat(index.nearby(city, MINI, HERE, 1_000, 1)).extracting(Candidate::driverId)
                .containsExactly(drivers.getFirst());
        assertThat(index.nearby(city, MINI, HERE, 1_000, 2)).extracting(Candidate::driverId)
                .containsExactlyElementsOf(drivers.subList(0, 2));
        assertThat(index.nearby(city, MINI, HERE, 1_000, 0)).isEmpty();
    }

    @Test
    void theRadiusIsMeasuredByHaversineDistance() {
        UUID inside = at(north(999.9));
        at(north(1_000.1));

        assertThat(index.nearby(city, MINI, HERE, 1_000, 5)).extracting(Candidate::driverId, Candidate::distanceM)
                .containsExactly(tuple(inside, 1_000));
    }

    @Test
    void equallyDistantDriversComeInDriverIdOrder() {
        // UUID order compares signed halves, so it isn't the order of the ids as text, which Valkey keeps ties in.
        List<UUID> drivers = Stream.of("00000000", "40000000", "7fffffff", "80000000", "c0000000", "ffffffff")
                .map(high -> UUID.fromString(high + "-0000-7000-8000-000000000001"))
                .toList();
        for (UUID driver : drivers) {
            index.mirror(city, driver, new MirrorState(Status.AVAILABLE, 1, MINI, null));
            send(driver, 1, north(100));
        }

        assertThat(index.nearby(city, MINI, HERE, 1_000, 6)).extracting(Candidate::driverId)
                .containsExactlyElementsOf(drivers.stream().sorted().toList())
                .doesNotContainSequence(drivers);
    }

    @Test
    void aSweepTakesDriversLastHeardInTheMinuteBeforeTheCutoffOutOfMatching() {
        UUID early = at(north(100));
        Instant first = clock.instant();
        clock.advance(Duration.ofMinutes(1));
        UUID late = at(north(200));

        assertThat(index.sweep(city, clock.instant())).as("the window starts a minute before the cutoff, inclusive")
                .containsExactly(early);
        assertThat(index.nearby(city, MINI, HERE, 1_000, 5)).extracting(Candidate::driverId).containsExactly(late);

        assertThat(index.sweep(city, clock.instant().plusMillis(1))).containsExactly(late);
        assertThat(index.nearby(city, MINI, HERE, 1_000, 5)).as("fresh, but swept").isEmpty();
        assertThat(index.lastSeen(city, List.of(early, late))).as("sweeping isn't hearing")
                .isEqualTo(Map.of(early, first, late, clock.instant()));

        send(late, 2, north(200));
        assertThat(index.nearby(city, MINI, HERE, 1_000, 5)).extracting(Candidate::driverId).containsExactly(late);
    }

    @Test
    void lastSeenAnswersOnlyForDriversHeardFrom() {
        UUID heard = at(north(100));
        Instant heardAt = clock.instant();
        UUID silent = available(MINI, 1);
        clock.advance(Duration.ofSeconds(5));

        assertThat(index.lastSeen(city, List.of(heard, silent, Ids.newId()))).isEqualTo(Map.of(heard, heardAt));
    }

    @Test
    void aBegunEpochPrecedesWhatTheIndexHoldsAndStaysPut() {
        index.beginEpoch(city);
        Instant epoch = index.epoch(city);
        assertThat(epoch).isBeforeOrEqualTo(clock.instant());

        at(north(100));
        clock.advance(Duration.ofMinutes(1));

        assertThat(index.epoch(city)).isEqualTo(epoch);
    }

    @Test
    void anEpochBeginsOncePerCity() {
        assertThat(index.epoch(city)).as("no older than now before it begins").isBeforeOrEqualTo(clock.instant());

        assertThat(index.beginEpoch(city)).isTrue();
        assertThat(index.beginEpoch(city)).isFalse();
        assertThat(index.beginEpoch(TestCities.newId())).isTrue();
    }

    @Test
    void sequenceNumbersAreComparedExactlyEvenBeyondADoublesPrecision() {
        UUID driver = available(MINI, 1);
        long large = (1L << 53) + 2;

        assertThat(send(driver, large, north(100)).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(send(driver, large - 1, north(200)).outcome()).isEqualTo(Outcome.STALE);
        assertThat(send(driver, large + 1, north(300)).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(send(driver, 99, north(400)).outcome()).as("fewer digits").isEqualTo(Outcome.STALE);
        assertThat(send(driver, Long.MAX_VALUE, north(500)).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(index.position(city, driver)).hasValueSatisfying(position ->
                assertThat(position.seq()).isEqualTo(Long.MAX_VALUE));
    }

    @Test
    void aCategoryChangeWithoutGoingOfflineMovesTheDriverToTheNewCategory() {
        UUID driver = at(north(100));

        assertThat(index.mirror(city, driver, new MirrorState(Status.AVAILABLE, 3, SEDAN, null))).isTrue();

        assertThat(index.nearby(city, MINI, HERE, 1_000, 5)).isEmpty();
        assertThat(index.nearby(city, SEDAN, HERE, 1_000, 5))
                .containsExactly(new Candidate(driver, north(100), 100, clock.instant()));
        assertThat(index.update(city, driver, MINI, update(2, north(100))))
                .isEqualTo(new UpdateResult(Outcome.CATEGORY_MISMATCH, Status.AVAILABLE, SEDAN, null));
        assertThat(index.mirrored(city)).containsEntry(driver, new MirrorState(Status.AVAILABLE, 3, SEDAN, null));
    }

    @Test
    void theSnapshotShowsOnlineDriversWithAPositionInsideTheBox() {
        UUID available = at(north(100));
        UUID assigned = at(north(200));
        UUID ride = Ids.newId();
        index.mirror(city, assigned, new MirrorState(Status.ASSIGNED, 2, MINI, ride));
        UUID silent = at(north(300));
        Instant silentSince = clock.instant();
        clock.advance(Duration.ofMinutes(1));
        index.sweep(city, clock.instant());
        send(available, 2, north(100));
        send(assigned, 2, north(200));
        available(MINI, 1);
        at(north(5_000));
        at(north(1_005));
        UUID offline = at(north(400));
        index.mirror(city, offline, new MirrorState(Status.OFFLINE, 2, null, null));

        assertThat(index.snapshot(city, around(HERE, 1_000), 10)).as("nearest the centre first").containsExactly(
                new OnlineDriver(available, north(100), Status.AVAILABLE, MINI, null, clock.instant()),
                new OnlineDriver(assigned, north(200), Status.ASSIGNED, MINI, ride, clock.instant()),
                new OnlineDriver(silent, north(300), Status.AVAILABLE, MINI, null, silentSince));
        assertThat(index.snapshot(TestCities.newId(), around(HERE, 1_000), 10)).isEmpty();
    }

    @Test
    void theSnapshotKeepsTheDriversNearestTheCentreUpToMax() {
        UUID far = at(north(-300));
        UUID nearest = at(north(100));
        UUID middle = at(north(-200));

        assertThat(index.snapshot(city, around(HERE, 1_000), 2)).extracting(OnlineDriver::driverId)
                .containsExactly(nearest, middle);
        assertThat(index.snapshot(city, around(north(-300), 50), 5)).extracting(OnlineDriver::driverId)
                .containsExactly(far);
    }

    @Test
    void poorAccuracyIsASignOfLifeButNotAPosition() {
        UUID driver = at(north(100));
        Instant placed = clock.instant();
        clock.advance(Duration.ofSeconds(5));

        UpdateResult result = index.update(city, driver, MINI,
                new LocationUpdate(2, north(300), 100.5, null, null, clock.instant()));

        assertThat(result).isEqualTo(new UpdateResult(Outcome.APPLIED, Status.AVAILABLE, null, null,
                UpdateResult.POOR_ACCURACY));
        assertThat(index.position(city, driver)).contains(new LivePosition(north(100), placed, 2));
        assertThat(index.lastSeen(city, List.of(driver))).isEqualTo(Map.of(driver, clock.instant()));
        assertThat(index.nearby(city, MINI, HERE, 1_000, 5))
                .containsExactly(new Candidate(driver, north(100), 100, clock.instant()));
        assertThat(index.update(city, driver, MINI, new LocationUpdate(3, north(300), 100, null, null,
                clock.instant())).flags()).as("the limit itself is usable").isZero();
    }

    @Test
    void anImplausibleJumpIsUsedOnlyAsTheThirdInARow() {
        UUID driver = at(north(100));
        Instant placed = clock.instant();
        GeoPoint far = north(10_000);

        clock.advance(Duration.ofSeconds(1));
        assertThat(send(driver, 2, far).flags()).isEqualTo(UpdateResult.IMPLAUSIBLE);
        clock.advance(Duration.ofSeconds(1));
        assertThat(send(driver, 3, far).flags()).isEqualTo(UpdateResult.IMPLAUSIBLE);
        assertThat(index.position(city, driver)).contains(new LivePosition(north(100), placed, 3));
        assertThat(index.nearby(city, MINI, far, 1_000, 5)).isEmpty();

        clock.advance(Duration.ofSeconds(1));
        assertThat(send(driver, 4, far).flags()).as("re-anchored, maybe after a tunnel").isZero();
        assertThat(index.position(city, driver)).contains(new LivePosition(far, clock.instant(), 4));
        assertThat(index.nearby(city, MINI, far, 1_000, 5)).extracting(Candidate::driverId).containsExactly(driver);
    }

    @Test
    void poorAccuracyDoesntCountTowardsAnImplausibleStreak() {
        UUID driver = at(north(100));
        clock.advance(Duration.ofSeconds(1));
        assertThat(send(driver, 2, north(10_000)).flags()).isEqualTo(UpdateResult.IMPLAUSIBLE);
        clock.advance(Duration.ofSeconds(1));
        assertThat(index.update(city, driver, MINI, new LocationUpdate(3, north(10_000), 500, null, null,
                clock.instant())).flags()).isEqualTo(UpdateResult.POOR_ACCURACY);

        clock.advance(Duration.ofSeconds(1));
        assertThat(send(driver, 4, north(10_000)).flags()).as("the second implausible one in a row")
                .isEqualTo(UpdateResult.IMPLAUSIBLE);
    }

    @Test
    void aUsableUpdateEndsAnImplausibleStreak() {
        UUID driver = at(north(100));
        clock.advance(Duration.ofSeconds(1));
        assertThat(send(driver, 2, north(10_000)).flags()).isEqualTo(UpdateResult.IMPLAUSIBLE);
        clock.advance(Duration.ofSeconds(1));
        assertThat(send(driver, 3, north(120)).flags()).isZero();

        clock.advance(Duration.ofSeconds(1));
        assertThat(send(driver, 4, north(10_000)).flags()).isEqualTo(UpdateResult.IMPLAUSIBLE);
        clock.advance(Duration.ofSeconds(1));
        assertThat(send(driver, 5, north(10_000)).flags()).as("the streak started again")
                .isEqualTo(UpdateResult.IMPLAUSIBLE);
    }

    @Test
    void speedIsMeasuredFromTheLastUsablePositionOverASecondAtLeast() {
        UUID driver = at(north(100));

        assertThat(send(driver, 2, north(140)).flags()).as("40 m at once counts as 40 m/s").isZero();
        assertThat(send(driver, 3, north(185)).flags()).as("45 m/s").isEqualTo(UpdateResult.IMPLAUSIBLE);
        clock.advance(Duration.ofSeconds(2));
        assertThat(send(driver, 4, north(220)).flags()).as("80 m from the last usable position in 2 s").isZero();
    }

    @Test
    void mirroredHoldsTheStateOfEveryDriverIncludingTombstones() {
        UUID available = available(MINI, 1);
        UUID offline = Ids.newId();
        index.mirror(city, offline, new MirrorState(Status.OFFLINE, 3, null, null));
        UUID assigned = Ids.newId();
        UUID ride = Ids.newId();
        index.mirror(city, assigned, new MirrorState(Status.ASSIGNED, 2, MINI, ride));

        assertThat(index.mirrored(city)).isEqualTo(Map.of(
                available, new MirrorState(Status.AVAILABLE, 1, MINI, null),
                offline, new MirrorState(Status.OFFLINE, 3, null, null),
                assigned, new MirrorState(Status.ASSIGNED, 2, MINI, ride)));
    }

    @Test
    void citiesDontShareDrivers() {
        UUID driver = at(north(100));
        String other = TestCities.newId();

        assertThat(index.update(other, driver, MINI, update(2, north(100))).outcome()).isEqualTo(Outcome.OFFLINE);
        assertThat(index.nearby(other, MINI, HERE, 1_000, 5)).isEmpty();
        assertThat(index.mirrored(other)).isEmpty();
        assertThat(index.position(other, driver)).isEmpty();
    }

    @Test
    @Tag("race")
    void concurrentUpdatesKeepTheHighestSequenceNumber() throws Exception {
        UUID driver = available(MINI, 1);
        List<Long> seqs = new ArrayList<>(LongStream.rangeClosed(1, 2_000).boxed().toList());
        Collections.shuffle(seqs);

        concurrently(seqs, seq -> send(driver, seq, north(seq / 100.0)));

        assertThat(index.position(city, driver)).hasValueSatisfying(position -> {
            assertThat(position.seq()).isEqualTo(2_000);
            assertThat(position.position()).isEqualTo(north(20));
        });
    }

    @Test
    @Tag("race")
    void concurrentMirrorWritesKeepTheHighestVersion() throws Exception {
        UUID driver = Ids.newId();
        List<Long> versions = new ArrayList<>(LongStream.rangeClosed(1, 400).boxed().toList());
        Collections.shuffle(versions);

        concurrently(versions, version -> index.mirror(city, driver,
                new MirrorState(version % 2 == 0 ? Status.AVAILABLE : Status.OFFERED, version, MINI, null)));

        assertThat(index.mirrored(city)).containsEntry(driver, new MirrorState(Status.AVAILABLE, 400, MINI, null));
    }

    /** A driver mirrored available in MINI at version {@code version}. */
    protected UUID available(String category, long version) {
        UUID driver = Ids.newId();
        index.mirror(city, driver, new MirrorState(Status.AVAILABLE, version, category, null));
        return driver;
    }

    /** An available MINI driver who just reported this position. */
    protected UUID at(GeoPoint position) {
        UUID driver = available(MINI, 1);
        assertThat(send(driver, 1, position).outcome()).isEqualTo(Outcome.APPLIED);
        return driver;
    }

    protected UpdateResult send(UUID driver, long seq, GeoPoint position) {
        return index.update(city, driver, MINI, update(seq, position));
    }

    protected LocationUpdate update(long seq, GeoPoint position) {
        return new LocationUpdate(seq, position, 5.0, null, null, clock.instant());
    }

    /** The point this many metres due north of {@link #HERE}, by the haversine distance; south when negative. */
    protected static GeoPoint north(double metres) {
        return new GeoPoint(HERE.lat() + Math.toDegrees(metres / EARTH_RADIUS_M), HERE.lon());
    }

    /** A box reaching this many metres north, south, east and west of the point. */
    protected static BoundingBox around(GeoPoint centre, double metres) {
        double lat = Math.toDegrees(metres / EARTH_RADIUS_M);
        double lon = lat / Math.cos(Math.toRadians(centre.lat()));
        return new BoundingBox(centre.lat() - lat, centre.lon() - lon, centre.lat() + lat, centre.lon() + lon);
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }

    private static void concurrently(List<Long> values, java.util.function.LongConsumer work) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> done = new ArrayList<>();
            for (long value : values) {
                done.add(pool.submit(() -> work.accept(value)));
            }
            for (Future<?> future : done) {
                future.get();
            }
        }
    }
}

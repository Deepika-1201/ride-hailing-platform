package com.ridehailing.location;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.location.LiveIndex.Candidate;
import com.ridehailing.location.LiveIndex.LivePosition;
import com.ridehailing.location.LiveIndex.LocationUpdate;
import com.ridehailing.location.LiveIndex.MirrorState;
import com.ridehailing.location.LiveIndex.Status;
import com.ridehailing.location.LiveIndex.UpdateResult;
import com.ridehailing.location.LiveIndex.UpdateResult.Outcome;
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
    void theEpochPrecedesWhatTheIndexHoldsAndStaysPut() {
        Instant epoch = index.epoch(city);
        assertThat(epoch).isBeforeOrEqualTo(clock.instant());

        at(north(100));
        clock.advance(Duration.ofMinutes(1));

        assertThat(index.epoch(city)).isEqualTo(epoch);
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

        concurrently(seqs, seq -> send(driver, seq, north(seq)));

        assertThat(index.position(city, driver)).hasValueSatisfying(position -> {
            assertThat(position.seq()).isEqualTo(2_000);
            assertThat(position.position()).isEqualTo(north(2_000));
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

    /** The point this many metres due north of {@link #HERE}, by the haversine distance. */
    protected static GeoPoint north(double metres) {
        return new GeoPoint(HERE.lat() + Math.toDegrees(metres / EARTH_RADIUS_M), HERE.lon());
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

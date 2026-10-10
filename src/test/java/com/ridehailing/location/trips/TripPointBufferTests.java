package com.ridehailing.location.trips;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.support.Eventually;
import com.ridehailing.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** LLD §9.8: the trip point buffer writes in batches, keeps what a failed write left, and drops what it must. */
class TripPointBufferTests {

    private static final UUID RIDE = UUID.randomUUID();

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T09:00:00Z"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final FakeRepository repository = new FakeRepository();
    private final TripPointBuffer buffer = new TripPointBuffer(repository, clock, meters);

    @AfterEach
    void stop() {
        buffer.stop();
    }

    @Test
    void aFlushWritesFiveHundredPointsToAStatement() {
        add(1, 1_200);

        assertThat(buffer.flush()).isEqualTo(1_200);

        assertThat(repository.statements()).extracting(List::size).containsExactly(500, 500, 200);
        assertThat(buffer.buffered()).isZero();
    }

    @Test
    void aFailedWriteKeepsItsPointsInOrderForTheNextFlush() {
        add(1, 700);
        repository.failFrom(2);

        assertThat(buffer.flush()).isEqualTo(500);
        add(701, 710);
        repository.failFrom(Integer.MAX_VALUE);
        assertThat(buffer.flush()).isEqualTo(210);

        assertThat(repository.written()).containsExactlyElementsOf(LongStream.rangeClosed(1, 710).boxed().toList());
        assertThat(dropped()).isZero();
    }

    @Test
    void aFailedWriteDropsPointsOlderThanThirtySeconds() {
        add(1, 3);
        clock.advance(Duration.ofSeconds(20));
        add(4, 5);
        clock.advance(Duration.ofSeconds(10).plusMillis(1));
        repository.failFrom(1);

        assertThat(buffer.flush()).isZero();

        assertThat(buffer.buffered()).isEqualTo(2);
        assertThat(dropped()).isEqualTo(3);
        repository.failFrom(Integer.MAX_VALUE);
        buffer.flush();
        assertThat(repository.written()).containsExactly(4L, 5L);
    }

    @Test
    void beyondFiftyThousandPointsTheOldestAreDropped() {
        add(1, 50_002);

        assertThat(buffer.buffered()).isEqualTo(50_000);
        assertThat(dropped()).isEqualTo(2);
        buffer.flush();
        assertThat(repository.written()).hasSize(50_000).startsWith(3L).endsWith(50_002L);
    }

    @Test
    void theLoopWritesFiveHundredPointsAtOnceAndStoppingWritesTheRest() {
        buffer.start();
        add(1, 500);

        // Sooner than the loop's 2 s wait: the 500th point wakes it.
        Eventually.within(Duration.ofSeconds(1), () -> assertThat(repository.written()).hasSize(500));
        add(501, 510);
        buffer.stop();

        assertThat(buffer.isRunning()).isFalse();
        assertThat(repository.written()).hasSize(510);
    }

    @Test
    void aFlushReturnsOnlyAfterAWriteAlreadyUnderWay() throws Exception {
        add(1, 3);
        repository.holdWrites();
        Thread loop = Thread.ofVirtual().start(buffer::flush);
        repository.awaitWriting();

        CompletableFuture<Integer> byHand = CompletableFuture.supplyAsync(buffer::flush);

        assertThatThrownBy(() -> byHand.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        repository.releaseWrites();
        assertThat(byHand.get(5, TimeUnit.SECONDS)).as("the loop took every point").isZero();
        loop.join();
        assertThat(repository.written()).containsExactly(1L, 2L, 3L);
    }

    private void add(long fromSeq, long toSeq) {
        for (long seq = fromSeq; seq <= toSeq; seq++) {
            buffer.add(new TripPoint(RIDE, seq, RIDE, clock.instant(), null, 12.9, 77.6, 5, null, null, 0));
        }
    }

    private double dropped() {
        return meters.counter("trip.points.dropped").count();
    }

    /** Fails every statement from the n-th on, counting from 1; can hold writes until released. */
    private static final class FakeRepository extends TripPointRepository {

        private final List<List<TripPoint>> statements = new ArrayList<>();
        private final CountDownLatch writing = new CountDownLatch(1);
        private volatile CountDownLatch released = new CountDownLatch(0);
        private int calls;
        private int failFrom = Integer.MAX_VALUE;

        FakeRepository() {
            super(null);
        }

        @Override
        public void insert(List<TripPoint> points) {
            writing.countDown();
            try {
                released.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            record(points);
        }

        void holdWrites() {
            released = new CountDownLatch(1);
        }

        void awaitWriting() throws InterruptedException {
            assertThat(writing.await(5, TimeUnit.SECONDS)).as("a write began").isTrue();
        }

        void releaseWrites() {
            released.countDown();
        }

        private synchronized void record(List<TripPoint> points) {
            if (++calls >= failFrom) {
                throw new DataAccessResourceFailureException("PostgreSQL is down");
            }
            statements.add(List.copyOf(points));
        }

        synchronized void failFrom(int call) {
            calls = 0;
            failFrom = call;
        }

        synchronized List<List<TripPoint>> statements() {
            return List.copyOf(statements);
        }

        synchronized List<Long> written() {
            return statements.stream().flatMap(List::stream).map(TripPoint::seq).toList();
        }
    }
}

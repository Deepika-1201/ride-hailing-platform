package com.ridehailing.notification.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.notification.db.DeliveryRepository.Claimed;
import com.ridehailing.notification.push.Push;
import com.ridehailing.platform.EventEnvelope;
import com.ridehailing.platform.Poller;
import com.ridehailing.platform.Role;
import com.ridehailing.shared.Ids;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.node.JsonNodeFactory;

/** LLD §15.4: sending, backing off, dying, leases and late answers, and what the counters count. */
class DeliveryTests extends NotificationTest {

    private static final String UNAVAILABLE = "push service unavailable";

    @Autowired
    Notifications notifications;

    @Autowired
    ObjectProvider<Poller> pollers;

    private final List<Push> pushed = new CopyOnWriteArrayList<>();

    @Test
    void aDueDeliveryIsSentOnceWithItsNotification() {
        UUID ride = Ids.newId();
        UUID recipient = Ids.newId();
        UUID delivery = notification(recipient, NotificationKind.TRIP_STARTED, ride);
        double sent = counted(NotificationMetrics.SENT);

        assertThat(sendAll(executorWith(pushed::add))).isEqualTo(1);

        assertThat(pushed).singleElement().satisfies(push -> {
            assertThat(push.deliveryId()).isEqualTo(delivery);
            assertThat(push.recipientId()).isEqualTo(recipient);
            assertThat(push.kind()).isEqualTo("TRIP_STARTED");
            assertThat(push.rideId()).isEqualTo(ride);
            assertThat(push.payload().get("ride_id").asString()).isEqualTo(ride.toString());
        });
        Told told = delivery(delivery);
        assertThat(told.status()).isEqualTo("SENT");
        assertThat(told.attempts()).isEqualTo(1);
        assertThat(told.lastError()).isNull();
        assertThat(jdbc.sql("SELECT sent_at IS NOT NULL FROM notification.deliveries WHERE id = :id")
                .param("id", delivery).query(Boolean.class).single()).isTrue();
        assertThat(sendAll(executorWith(pushed::add))).as("a sent delivery is never sent again").isZero();
        assertThat(counted(NotificationMetrics.SENT) - sent).isEqualTo(1);
    }

    @Test
    void theDeliveryDueLongestIsSentFirst() {
        UUID dueForOneSecond = notification(Ids.newId(), NotificationKind.TRIP_STARTED, Ids.newId());
        UUID dueForThreeSeconds = notification(Ids.newId(), NotificationKind.TRIP_STARTED, Ids.newId());
        UUID dueForTwoSeconds = notification(Ids.newId(), NotificationKind.TRIP_STARTED, Ids.newId());
        dueFor(dueForOneSecond, 1);
        dueFor(dueForThreeSeconds, 3);
        dueFor(dueForTwoSeconds, 2);

        assertThat(sendAll(executorWith(pushed::add))).isEqualTo(3);

        assertThat(pushed).extracting(Push::deliveryId).containsExactly(dueForThreeSeconds, dueForTwoSeconds,
                dueForOneSecond);
    }

    @Test
    void failedSendsWaitOneFiveAndThirtySecondsThenTwoAndFiveMinutesThenTheDeliveryIsDead() {
        UUID delivery = notification(Ids.newId(), NotificationKind.DRIVER_ARRIVED, Ids.newId());
        DeliveryExecutor failing = executorWith(push -> {
            throw new IllegalStateException(UNAVAILABLE);
        });
        double failed = counted(NotificationMetrics.FAILED);
        double dead = counted(NotificationMetrics.DEAD);
        List<Duration> waits = new ArrayList<>();

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(failing.sendNext()).isTrue();
            Told told = delivery(delivery);
            assertThat(told.status()).isEqualTo("PENDING");
            assertThat(told.attempts()).isEqualTo(attempt);
            assertThat(told.lastError()).isEqualTo(UNAVAILABLE);
            waits.add(told.dueIn());
            dueNow(delivery);
        }
        assertThat(failing.sendNext()).isTrue();

        List<Duration> backoff = List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofMinutes(2), Duration.ofMinutes(5));
        for (int i = 0; i < backoff.size(); i++) {
            assertThat(waits.get(i)).as("the wait after failure %d", i + 1)
                    .isBetween(backoff.get(i).minusSeconds(1), backoff.get(i));
        }
        Told told = delivery(delivery);
        assertThat(told.status()).isEqualTo("DEAD");
        assertThat(told.attempts()).isEqualTo(6);
        assertThat(told.lastError()).isEqualTo(UNAVAILABLE);
        dueNow(delivery);
        assertThat(sendAll(executorWith(pushed::add))).as("a dead delivery is never sent").isZero();
        assertThat(counted(NotificationMetrics.FAILED) - failed).isEqualTo(5);
        assertThat(counted(NotificationMetrics.DEAD) - dead).isEqualTo(1);
    }

    @Test
    void aFailureWithoutAMessageKeepsTheExceptionsNameAndALongOneIsCut() {
        UUID quiet = notification(Ids.newId(), NotificationKind.DRIVER_ARRIVED, Ids.newId());
        executorWith(push -> {
            throw new UnsupportedOperationException();
        }).sendNext();
        UUID loud = notification(Ids.newId(), NotificationKind.DRIVER_ARRIVED, Ids.newId());
        executorWith(push -> {
            throw new IllegalStateException("x".repeat(600));
        }).sendNext();

        assertThat(delivery(quiet).lastError()).isEqualTo("UnsupportedOperationException");
        assertThat(delivery(loud).lastError()).isEqualTo("x".repeat(500));
    }

    @Test
    void aClaimLeasesTheDeliveryForThirtySeconds() {
        UUID delivery = notification(Ids.newId(), NotificationKind.TRIP_STARTED, Ids.newId());

        Optional<Claimed> claimed = claim();
        Optional<Claimed> again = claim();

        assertThat(claimed).hasValueSatisfying(claim -> {
            assertThat(claim.id()).isEqualTo(delivery);
            assertThat(claim.attempts()).isEqualTo(1);
            assertThat(claim.channel()).isEqualTo("PUSH");
        });
        assertThat(delivery(delivery).dueIn()).isBetween(Duration.ofSeconds(29), Duration.ofSeconds(30));
        assertThat(again).as("leased").isEmpty();
        dueNow(delivery);
        assertThat(claim()).as("the lease ran out").hasValueSatisfying(claim -> assertThat(claim.attempts())
                .isEqualTo(2));
    }

    @Test
    void aSendCutShortByACrashIsSentAgainOnceTheLeasePasses() {
        UUID delivery = notification(Ids.newId(), NotificationKind.TRIP_STARTED, Ids.newId());
        assertThat(claim()).isPresent();

        assertThat(sendAll(executorWith(pushed::add))).as("leased by the crashed send").isZero();
        dueNow(delivery);
        assertThat(sendAll(executorWith(pushed::add))).isEqualTo(1);

        assertThat(delivery(delivery).status()).isEqualTo("SENT");
        assertThat(delivery(delivery).attempts()).isEqualTo(2);
    }

    @Test
    void anAnswerFromAClaimThatWasTakenOverChangesNothing() {
        UUID delivery = notification(Ids.newId(), NotificationKind.TRIP_STARTED, Ids.newId());
        double failed = counted(NotificationMetrics.FAILED);
        // The provider stalls past the lease: another worker claims the delivery before the first one's answer.
        DeliveryExecutor stalled = executorWith(push -> {
            dueNow(delivery);
            assertThat(claim()).isPresent();
            throw new IllegalStateException(UNAVAILABLE);
        });

        assertThat(stalled.sendNext()).isTrue();

        Told told = delivery(delivery);
        assertThat(told.status()).isEqualTo("PENDING");
        assertThat(told.attempts()).isEqualTo(2);
        assertThat(told.lastError()).as("the later claim's send is still out").isNull();
        assertThat(told.dueIn()).as("the later claim's lease").isGreaterThan(Duration.ofSeconds(25));
        assertThat(counted(NotificationMetrics.FAILED) - failed).isZero();
    }

    @Test
    void aLastFailureFromAClaimThatWasTakenOverKillsNothing() {
        UUID delivery = notification(Ids.newId(), NotificationKind.TRIP_STARTED, Ids.newId());
        jdbc.sql("UPDATE notification.deliveries SET attempts = 5 WHERE id = :id").param("id", delivery).update();
        double dead = counted(NotificationMetrics.DEAD);
        DeliveryExecutor stalled = executorWith(push -> {
            dueNow(delivery);
            assertThat(claim()).isPresent();
            throw new IllegalStateException(UNAVAILABLE);
        });

        assertThat(stalled.sendNext()).isTrue();

        assertThat(delivery(delivery).status()).isEqualTo("PENDING");
        assertThat(delivery(delivery).attempts()).isEqualTo(7);
        assertThat(counted(NotificationMetrics.DEAD) - dead).isZero();
    }

    @Test
    void aSendRecordedByAnotherWorkerIsCountedOnce() {
        UUID delivery = notification(Ids.newId(), NotificationKind.TRIP_STARTED, Ids.newId());
        double sent = counted(NotificationMetrics.SENT);
        DeliveryExecutor late = executorWith(push -> transactions.run(() -> deliveries.sent(push.deliveryId())));

        assertThat(late.sendNext()).isTrue();

        assertThat(delivery(delivery).status()).isEqualTo("SENT");
        assertThat(counted(NotificationMetrics.SENT) - sent).isZero();
        assertThat(transactions.execute(() -> deliveries.dead(delivery, 1, UNAVAILABLE))).isFalse();
        assertThat(transactions.execute(() -> deliveries.retryAfter(delivery, 1, UNAVAILABLE, Duration.ZERO)))
                .isFalse();
        assertThat(delivery(delivery).status()).isEqualTo("SENT");
    }

    @Test
    void workersSendingAtOnceNeverSendADeliveryTwice() throws Exception {
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            created.add(notification(Ids.newId(), NotificationKind.TRIP_STARTED, Ids.newId()));
        }
        List<UUID> sent = new CopyOnWriteArrayList<>();

        try (ExecutorService workers = Executors.newFixedThreadPool(4)) {
            List<Future<Integer>> running = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                DeliveryExecutor executor = executorWith(push -> sent.add(push.deliveryId()));
                running.add(workers.submit(() -> sendAll(executor)));
            }
            for (Future<Integer> worker : running) {
                worker.get(60, TimeUnit.SECONDS);
            }
        }

        assertThat(sent).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(created);
    }

    @Test
    void theOutcomeCountersAreExportedForPushes() {
        String scrape = get(managementPort, "/actuator/prometheus").body();

        for (String outcome : List.of("SENT", "FAILED", "DEAD")) {
            assertThat(scrape).contains("notification_deliveries_total{channel=\"PUSH\",outcome=\"" + outcome + "\"");
        }
    }

    @Test
    void workerNodesPollForDueDeliveriesTwiceASecond() {
        Poller poller = pollers.stream().filter(candidate -> candidate.name().equals("notification-deliveries"))
                .findFirst().orElseThrow();
        UUID delivery = notification(Ids.newId(), NotificationKind.TRIP_STARTED, Ids.newId());

        assertThat(poller.role()).isEqualTo(Role.WORKER);
        assertThat(poller.threads()).isEqualTo(1);
        assertThat(poller.interval()).isEqualTo(Duration.ofMillis(500));
        assertThat(poller.poll()).isTrue();
        assertThat(delivery(delivery).status()).as("sent by the logging mock").isEqualTo("SENT");
        assertThat(poller.poll()).isFalse();
    }

    /** A notification of an event of its own, as a consumer makes it; answers its delivery. */
    private UUID notification(UUID recipient, NotificationKind kind, UUID ride) {
        EventEnvelope event = new EventEnvelope(Ids.newId(), "TripStarted", 1, "ride", ride, 1, ride, Instant.now(),
                "ride/api", ride.toString(), null, null, JsonNodeFactory.instance.objectNode());
        transactions.run(() -> notifications.notify(event, recipient, kind, ride,
                Notifications.payload().put("ride_id", ride.toString())));
        return toldBy(event.eventId()).getFirst().deliveryId();
    }

    private Optional<Claimed> claim() {
        return transactions.execute(() -> deliveries.claimDue(properties.lease()));
    }

    private void dueFor(UUID deliveryId, int seconds) {
        jdbc.sql("UPDATE notification.deliveries SET next_attempt_at = now() - make_interval(secs => :s) WHERE id = :id")
                .param("s", seconds)
                .param("id", deliveryId)
                .update();
    }
}

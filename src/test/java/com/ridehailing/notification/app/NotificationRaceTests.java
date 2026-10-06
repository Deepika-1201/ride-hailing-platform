package com.ridehailing.notification.app;

import static com.ridehailing.support.TestRides.asApi;
import static com.ridehailing.support.TestRides.asWorker;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.outbox.EventDelivery;
import com.ridehailing.ride.events.DriverUnassigned;
import com.ridehailing.shared.Ids;
import com.ridehailing.support.RaceRunner;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Race 7 (LLD §15.4, §17.2): the same event delivered twice at once makes one notification per recipient and kind,
 * each with one delivery, whether both deliveries go through the inbox or one goes past it.
 */
class NotificationRaceTests extends NotificationTest {

    @Autowired
    Outbox outbox;

    @Autowired
    EventDelivery events;

    @Test
    void anEventDeliveredTwiceAtOnceThroughTheInboxIsHandledOnce() throws InterruptedException {
        Map<String, Integer> seen = new TreeMap<>();
        for (int i = 0; i < RaceRunner.repetitions(); i++) {
            UUID event = unreachableDriver();

            List<String> outcomes = RaceRunner.race(deliver(event), deliver(event));

            assertThat(outcomes).as("repetition %d", i).containsExactlyInAnyOrder("handled", "already handled");
            assertToldOnce(event, i);
            seen.merge(outcomes.getFirst(), 1, Integer::sum);
        }
        RaceRunner.assertExplored(seen, "handled", "already handled");
    }

    @Test
    void anEventDeliveredAtOnceThroughTheInboxAndPastItTellsEachRecipientOnce() throws InterruptedException {
        for (int i = 0; i < RaceRunner.repetitions(); i++) {
            UUID event = unreachableDriver();

            List<String> outcomes = RaceRunner.staggered(deliver(event), replay(event));

            assertThat(outcomes).as("repetition %d", i).containsExactly("handled", "replayed");
            assertToldOnce(event, i);
        }
    }

    @Test
    void anEventReplayedTwiceAtOnceTellsEachRecipientOnce() throws InterruptedException {
        for (int i = 0; i < RaceRunner.repetitions(); i++) {
            UUID event = unreachableDriver();

            List<String> outcomes = RaceRunner.race(replay(event), replay(event));

            assertThat(outcomes).as("repetition %d", i).containsExactly("replayed", "replayed");
            assertToldOnce(event, i);
        }
    }

    /** {@code DriverUnassigned} for an unreachable driver tells two people: the rider and the driver. */
    private UUID unreachableDriver() {
        UUID ride = Ids.newId();
        UUID rider = Ids.newId();
        UUID driver = Ids.newId();
        asApi(() -> {
            transactions.run(() -> outbox.append(DomainEvent.of(DriverUnassigned.TYPE, DriverUnassigned.VERSION,
                    "ride", ride, 3, new DriverUnassigned(ride, rider, driver, "DRIVER_UNREACHABLE", 2,
                            Instant.now()))));
            return null;
        });
        return jdbc.sql("SELECT event_id FROM platform.outbox WHERE aggregate_id = :ride")
                .param("ride", ride)
                .query(UUID.class)
                .single();
    }

    private Callable<String> deliver(UUID event) {
        return () -> asWorker(() -> events.deliver(RideNotifications.NAME, event)) ? "handled" : "already handled";
    }

    private Callable<String> replay(UUID event) {
        return () -> asWorker(() -> {
            events.replay(RideNotifications.NAME, event);
            return "replayed";
        });
    }

    private void assertToldOnce(UUID event, int repetition) {
        assertThat(toldBy(event)).as("repetition %d", repetition)
                .extracting(Told::kind, Told::deliveries)
                .containsExactly(tuple("DRIVER_UNASSIGNED", 1), tuple("DRIVER_UNASSIGNED", 1));
    }
}

package com.ridehailing.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.FailedDeliveries;
import com.ridehailing.platform.InstanceId;
import com.ridehailing.platform.Leases;
import com.ridehailing.platform.LogContext;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.leases.LeaseProperties;
import com.ridehailing.platform.workers.WorkerProperties;
import com.ridehailing.support.Effects;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.PlatformTables;
import com.ridehailing.support.ScriptedConsumer;
import com.ridehailing.support.TestHandlers;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * LLD §5.2, §5.3: every committed event reaches every subscribed consumer, and each consumer's effect happens once,
 * across handler failures, poison events, a relay crash between delivery and marking, and a lease takeover.
 */
class OutboxRelayTests extends IntegrationTest {

    private static final String FIRST = TestHandlers.FIRST_CONSUMER;
    private static final String SECOND = TestHandlers.SECOND_CONSUMER;

    @Autowired
    private Outbox outbox;

    @Autowired
    private FailedDeliveries failedDeliveries;

    @Autowired
    private ScriptedConsumer firstConsumer;

    @Autowired
    private ScriptedConsumer secondConsumer;

    @Autowired
    private OutboxRows rows;

    @Autowired
    private EventConsumers consumers;

    @Autowired
    private ConsumerDelivery delivery;

    @Autowired
    private Leases leases;

    @Autowired
    private OutboxProperties properties;

    @Autowired
    private LeaseProperties leaseProperties;

    @Autowired
    private TransactionTemplate template;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        PlatformTables.reset(jdbc);
        Effects.reset(jdbc);
        firstConsumer.reset();
        secondConsumer.reset();
    }

    @Test
    void deliversEachEventOnceToEverySubscribedConsumerInIdOrderThenMarksItPublished() {
        List<String> events = List.of(append("a"), append("b"), append("c"));
        OutboxRelay relay = relay("node-a");

        assertThat(relay.relayBatch()).isFalse();
        relay.relayBatch();

        assertThat(Effects.refs(jdbc, FIRST)).containsExactlyElementsOf(events);
        assertThat(Effects.refs(jdbc, SECOND)).containsExactlyElementsOf(events);
        assertThat(unpublished()).isZero();
    }

    @Test
    void aFailureAfterTheHandlersWriteIsRolledBackAndTheRetryAppliesItOnce() {
        String event = append("a");
        firstConsumer.script.failTimes(UUID.fromString(event), 2);

        relay("node-a").relayBatch();

        assertThat(Effects.count(jdbc, FIRST, event)).isEqualTo(1);
        assertThat(failedDeliveryCount()).isZero();
        assertThat(unpublished()).isZero();
    }

    @Test
    void aPoisonEventIsSetAsideForItsConsumerOnlyAndTheStreamMovesOn() {
        String poison = append("a");
        String next = append("b");
        firstConsumer.script.failAlways(UUID.fromString(poison));

        relay("node-a").relayBatch();

        assertThat(Effects.refs(jdbc, FIRST)).containsExactly(next);
        assertThat(Effects.refs(jdbc, SECOND)).containsExactly(poison, next);
        assertThat(unpublished()).isZero();
        Map<String, Object> failed = jdbc.sql("SELECT * FROM platform.failed_deliveries").query().singleRow();
        assertThat(failed).containsEntry("consumer", FIRST)
                .containsEntry("event_id", UUID.fromString(poison))
                .containsEntry("attempts", 1 + properties.retryDelays().size())
                .containsEntry("redriven_at", null);
        assertThat((String) failed.get("last_error")).contains("Scripted failure in " + FIRST);
    }

    @Test
    void aRedriveDeliversTheSetAsideEventOnceAndResolvesIt() {
        String poison = append("a");
        firstConsumer.script.failAlways(UUID.fromString(poison));
        relay("node-a").relayBatch();
        firstConsumer.script.heal(UUID.fromString(poison));

        assertThat(failedDeliveries.redrive(FIRST, UUID.fromString(poison))).isTrue();

        assertThat(Effects.count(jdbc, FIRST, poison)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT redriven_at IS NOT NULL FROM platform.failed_deliveries")
                .query(Boolean.class).single()).isTrue();
        assertThatThrownBy(() -> failedDeliveries.redrive(FIRST, UUID.fromString(poison)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void aRedriveThatFailsAgainKeepsTheEventAside() {
        String poison = append("a");
        firstConsumer.script.failAlways(UUID.fromString(poison));
        relay("node-a").relayBatch();

        assertThat(failedDeliveries.redrive(FIRST, UUID.fromString(poison))).isFalse();

        Map<String, Object> failed = jdbc.sql("SELECT attempts, redriven_at FROM platform.failed_deliveries")
                .query().singleRow();
        assertThat(failed).containsEntry("attempts", 2 + properties.retryDelays().size())
                .containsEntry("redriven_at", null);
        assertThat(Effects.count(jdbc, FIRST, poison)).isZero();
    }

    @Test
    void aRelayCrashBetweenDeliveryAndMarkingLosesNothingAndDuplicatesNothing() {
        String delivered = append("a");
        String crashing = append("b");
        secondConsumer.afterEffect(event -> {
            if (event.eventId().toString().equals(crashing)) {
                throw new SimulatedCrash();
            }
        });
        OutboxRelay crashed = relay("node-a");

        assertThatThrownBy(crashed::relayBatch).isInstanceOf(SimulatedCrash.class);
        assertThat(unpublished()).isEqualTo(2);
        secondConsumer.reset();
        expireLease();
        relay("node-b").relayBatch();

        for (String event : List.of(delivered, crashing)) {
            assertThat(Effects.count(jdbc, FIRST, event)).as(event).isEqualTo(1);
            assertThat(Effects.count(jdbc, SECOND, event)).as(event).isEqualTo(1);
        }
        assertThat(unpublished()).isZero();
    }

    @Test
    void aRelayThatLostItsLeaseCannotMarkItsBatchAndTheNewHolderDeliversItWithoutDuplicates() {
        OutboxRelay stale = relay("node-a");
        OutboxRelay current = relay("node-b");
        append("a");
        stale.relayBatch();
        expireLease();
        current.relayBatch();
        String late = append("b");

        // Within its renewal period the stale relay doesn't know yet: it delivers, but the fenced update rejects it.
        stale.relayBatch();
        assertThat(unpublished()).isEqualTo(1);
        current.relayBatch();

        assertThat(Effects.count(jdbc, FIRST, late)).isEqualTo(1);
        assertThat(Effects.count(jdbc, SECOND, late)).isEqualTo(1);
        assertThat(unpublished()).isZero();
        assertThat(jdbc.sql("SELECT holder || ':' || token FROM platform.leases").query(String.class).single())
                .isEqualTo("node-b:2");
    }

    @Test
    void theTokenAloneFencesAStaleRelayWhoseSuccessorHasTheSameHolderName() {
        // A container restarted in place keeps its host name and PID 1; only the token tells the two apart.
        OutboxRelay stale = relay("restarted-node");
        OutboxRelay successor = relay("restarted-node");
        append("a");
        stale.relayBatch();
        expireLease();
        successor.relayBatch();
        String late = append("b");

        stale.relayBatch();
        assertThat(unpublished()).isEqualTo(1);
        successor.relayBatch();

        assertThat(Effects.count(jdbc, FIRST, late)).isEqualTo(1);
        assertThat(unpublished()).isZero();
    }

    @Test
    void anotherProcessHoldingTheLeaseKeepsThisRelayIdle() {
        String event = append("a");
        relay("node-a").relayBatch();
        append("b");

        relay("node-b").relayBatch();

        assertThat(Effects.refs(jdbc, FIRST)).containsExactly(event);
        assertThat(unpublished()).isEqualTo(1);
    }

    @Test
    void anEventNobodyConsumesIsStillPublished() {
        appendOfType("NobodyListens");

        relay("node-a").relayBatch();

        assertThat(unpublished()).isZero();
        assertThat(Effects.refs(jdbc, FIRST)).isEmpty();
    }

    @Test
    void aFullBatchAsksToRunAgainAtOnce() {
        append("a");
        append("b");
        append("c");
        OutboxRelay relay = new OutboxRelay(rows, consumers, delivery, leases, new InstanceId("node-a"),
                new OutboxProperties(properties.idlePoll(), 2, properties.retryDelays()), leaseProperties,
                new WorkerProperties(false));

        assertThat(relay.relayBatch()).isTrue();
        assertThat(relay.relayBatch()).isFalse();
        assertThat(unpublished()).isZero();
    }

    private OutboxRelay relay(String holder) {
        return new OutboxRelay(rows, consumers, delivery, leases, new InstanceId(holder), properties, leaseProperties,
                new WorkerProperties(false));
    }

    private String append(String note) {
        return appendOfType(ScriptedConsumer.EVENT_TYPE, note);
    }

    private String appendOfType(String type) {
        return appendOfType(type, "x");
    }

    /** Appends in its own transaction, as a request would, and returns the new event's ID. */
    private String appendOfType(String type, String note) {
        LogContext.run(Map.of(LogContext.ROLE, "api"), () -> template.executeWithoutResult(status ->
                outbox.append(DomainEvent.of(type, 1, "ride", UUID.randomUUID(), 1, new Noted(note)))));
        return jdbc.sql("SELECT event_id::text FROM platform.outbox ORDER BY id DESC LIMIT 1")
                .query(String.class)
                .single();
    }

    private long unpublished() {
        return jdbc.sql("SELECT count(*) FROM platform.outbox WHERE published_at IS NULL").query(Long.class).single();
    }

    private long failedDeliveryCount() {
        return jdbc.sql("SELECT count(*) FROM platform.failed_deliveries").query(Long.class).single();
    }

    private void expireLease() {
        jdbc.sql("UPDATE platform.leases SET expires_at = now() - interval '1 second' WHERE name = :name")
                .param("name", OutboxRelay.LEASE)
                .update();
    }

    record Noted(String note) {
    }

    /** Stands in for the process dying: an {@link Error}, which no retry loop catches. */
    static final class SimulatedCrash extends Error {
    }
}

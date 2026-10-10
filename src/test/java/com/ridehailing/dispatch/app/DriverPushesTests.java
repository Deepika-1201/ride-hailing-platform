package com.ridehailing.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.dispatch.DispatchApi.OfferStatus;
import com.ridehailing.dispatch.db.OfferRepository.OfferRow;
import com.ridehailing.platform.PushBus;
import com.ridehailing.platform.Transactions;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.PushInbox;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** LLD §14.7: a push goes once its transaction commits, and never after a rollback. */
class DriverPushesTests extends IntegrationTest {

    private final UUID driverId = UUID.randomUUID();

    @Autowired
    private DriverPushes pushes;

    @Autowired
    private Transactions transactions;

    @Autowired
    private PushBus push;

    @Test
    void aPushWaitsForItsTransactionToCommit() {
        try (PushInbox inbox = PushInbox.open(push, PushBus.driverChannel(driverId))) {
            transactions.run(() -> {
                pushes.offerWithdrawn(offer(), "EXPIRED");
                assertThat(inbox.messages()).as("before the commit").isEmpty();
            });

            assertThat(inbox.await(1)).singleElement().satisfies(message ->
                    assertThat(message.get("reason").asString()).isEqualTo("EXPIRED"));
        }
    }

    @Test
    void aRolledBackTransactionPushesNothing() {
        try (PushInbox inbox = PushInbox.open(push, PushBus.driverChannel(driverId))) {
            assertThatThrownBy(() -> transactions.run(() -> {
                pushes.offerWithdrawn(offer(), "EXPIRED");
                throw new IllegalStateException("Rolled back");
            })).hasMessage("Rolled back");

            pushes.offerWithdrawn(offer(), "SUSPENDED");

            assertThat(inbox.await(1)).as("only the push sent outside a transaction, at once").singleElement()
                    .satisfies(message -> assertThat(message.get("reason").asString()).isEqualTo("SUSPENDED"));
        }
    }

    private OfferRow offer() {
        Instant now = Instant.now();
        return new OfferRow(UUID.randomUUID(), UUID.randomUUID(), driverId, 1, OfferStatus.WITHDRAWN, 1, 100, now,
                now.plusSeconds(15), null, null, null, 1);
    }
}

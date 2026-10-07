package com.ridehailing.identity.tickets;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.identity.tickets.TicketStore.TicketClaims;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InMemoryTicketStoreTests extends TicketStoreContract {

    @Override
    protected TicketStore newStore(Clock clock) {
        return new InMemoryTicketStore(clock);
    }

    @Test
    void expiredTicketsGoWhenTheNextIsIssued() {
        store.issue(new TicketClaims(Ids.newId(), Set.of(UserRole.RIDER), null));
        clock.advance(TicketStore.TTL);

        store.issue(new TicketClaims(Ids.newId(), Set.of(UserRole.RIDER), null));

        assertThat(((InMemoryTicketStore) store).size()).isEqualTo(1);
    }
}

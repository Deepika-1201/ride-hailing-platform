package com.ridehailing.identity.tickets;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.identity.tickets.TicketStore.Ticket;
import com.ridehailing.identity.tickets.TicketStore.TicketClaims;
import com.ridehailing.platform.Valkey;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.Valkeys;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ValkeyTicketStoreTests extends TicketStoreContract {

    private final Valkey valkey = Valkeys.standalone();

    @Override
    protected TicketStore newStore(Clock clock) {
        return new ValkeyTicketStore(valkey, clock);
    }

    @Test
    void aTicketIsOneKeyThatValkeyDropsAfterSixtySeconds() {
        Ticket ticket = store.issue(new TicketClaims(Ids.newId(), Set.of(UserRole.DRIVER), "BLR"));

        Long ttl = valkey.await("test", Valkeys.TIMEOUTS.other(), valkey.commands().ttl("wsticket:" + ticket.value()));

        assertThat(ttl).isBetween(TicketStore.TTL.toSeconds() - 5, TicketStore.TTL.toSeconds());
        store.redeem(ticket.value());
        assertThat(valkey.await("test", Valkeys.TIMEOUTS.other(), valkey.commands().exists("wsticket:"
                + ticket.value()))).isZero();
    }
}

package com.ridehailing.identity.tickets;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.identity.tickets.TicketStore.Ticket;
import com.ridehailing.identity.tickets.TicketStore.TicketClaims;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What every ticket store must do (LLD §14.1): tickets good once, for 60 s by the application clock. */
abstract class TicketStoreContract {

    protected final MutableClock clock = new MutableClock(Instant.parse("2026-10-02T08:00:00Z"));
    protected TicketStore store;

    protected abstract TicketStore newStore(Clock clock);

    @BeforeEach
    void createStore() {
        store = newStore(clock);
    }

    @Test
    void aTicketGivesItsClaimsOnce() {
        TicketClaims claims = new TicketClaims(Ids.newId(), Set.of(UserRole.DRIVER, UserRole.RIDER), "BLR");
        Ticket ticket = store.issue(claims);

        assertThat(ticket.expiresAt()).isEqualTo(clock.instant().plus(TicketStore.TTL));
        assertThat(store.redeem(ticket.value())).contains(claims);
        assertThat(store.redeem(ticket.value())).isEmpty();
    }

    @Test
    void claimsWithoutACityOrRolesComeBackAsIssued() {
        TicketClaims rider = new TicketClaims(Ids.newId(), Set.of(UserRole.RIDER), null);
        TicketClaims nobody = new TicketClaims(Ids.newId(), Set.of(), null);

        assertThat(store.redeem(store.issue(rider).value())).contains(rider);
        assertThat(store.redeem(store.issue(nobody).value())).contains(nobody);
    }

    @Test
    void aTicketExpiresAfterSixtySeconds() {
        Ticket kept = store.issue(claims());
        Ticket late = store.issue(claims());

        clock.advance(TicketStore.TTL.minusMillis(1));
        assertThat(store.redeem(kept.value())).isPresent();
        clock.advance(Duration.ofMillis(1));
        assertThat(store.redeem(late.value())).isEmpty();
    }

    @Test
    void unknownTicketsFindNothing() {
        assertThat(store.redeem("no-such-ticket")).isEmpty();
        assertThat(store.redeem("")).isEmpty();
    }

    @Test
    void ticketsAreLongRandomAndUrlSafe() {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            values.add(store.issue(claims()).value());
        }

        assertThat(values).hasSize(100).allSatisfy(value -> assertThat(value).matches("[A-Za-z0-9_-]{43}"));
    }

    private static TicketClaims claims() {
        return new TicketClaims(Ids.newId(), Set.of(UserRole.RIDER), null);
    }
}

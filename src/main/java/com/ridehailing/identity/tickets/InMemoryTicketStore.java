package com.ridehailing.identity.tickets;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Tickets in this process's memory, for a single process (LLD §1.3); expired ones go when the next is issued. */
@Component
@ConditionalOnProperty(name = "ride.location.store", havingValue = "memory", matchIfMissing = true)
class InMemoryTicketStore implements TicketStore {

    private final Map<String, Stored> tickets = new ConcurrentHashMap<>();
    private final Clock clock;

    InMemoryTicketStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Ticket issue(TicketClaims claims) {
        Instant now = clock.instant();
        tickets.values().removeIf(stored -> !now.isBefore(stored.expiresAt()));
        Ticket ticket = new Ticket(Values.next(), now.plus(TTL));
        tickets.put(ticket.value(), new Stored(claims, ticket.expiresAt()));
        return ticket;
    }

    @Override
    public Optional<TicketClaims> redeem(String ticket) {
        Stored stored = tickets.remove(ticket);
        return stored != null && clock.instant().isBefore(stored.expiresAt()) ? Optional.of(stored.claims())
                : Optional.empty();
    }

    int size() {
        return tickets.size();
    }

    private record Stored(TicketClaims claims, Instant expiresAt) {
    }
}

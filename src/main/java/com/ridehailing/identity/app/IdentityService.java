package com.ridehailing.identity.app;

import com.ridehailing.identity.IdentityApi;
import com.ridehailing.identity.db.Users;
import com.ridehailing.identity.tickets.TicketStore;
import com.ridehailing.identity.tickets.TicketStore.Ticket;
import com.ridehailing.shared.UserRole;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class IdentityService implements IdentityApi {

    private final Users users;
    private final TicketStore tickets;

    IdentityService(Users users, TicketStore tickets) {
        this.users = users;
        this.tickets = tickets;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID ensureUser(String phone, Set<UserRole> roles) {
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("A user needs at least one role");
        }
        return users.ensure(phone, roles);
    }

    @Override
    public RealtimeTicket issueTicket(UUID userId, Set<UserRole> roles, String cityId) {
        Ticket ticket = tickets.issue(new TicketStore.TicketClaims(userId, roles, cityId));
        return new RealtimeTicket(ticket.value(), ticket.expiresAt());
    }

    @Override
    public Optional<TicketClaims> redeemTicket(String ticket) {
        return tickets.redeem(ticket).map(claims -> new TicketClaims(claims.userId(), claims.roles(), claims.cityId()));
    }
}

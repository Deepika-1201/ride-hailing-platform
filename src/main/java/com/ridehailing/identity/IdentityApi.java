package com.ridehailing.identity;

import com.ridehailing.shared.UserRole;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** User accounts, for the modules that onboard people, and WebSocket tickets (LLD §2.2, §14.1). */
public interface IdentityApi {

    /**
     * The phone's user ID: a new user with the roles, or an existing one with the roles added, so a rider who becomes
     * a driver keeps their account. Joins the caller's transaction.
     */
    UUID ensureUser(String phone, Set<UserRole> roles);

    /** A single-use ticket for the WebSocket handshake, good for 60 s; {@code cityId} may be null. */
    RealtimeTicket issueTicket(UUID userId, Set<UserRole> roles, String cityId);

    /** The ticket's claims, once: an unknown, used or expired ticket finds nothing. */
    Optional<TicketClaims> redeemTicket(String ticket);

    record RealtimeTicket(String ticket, Instant expiresAt) {
    }

    /** {@code cityId} may be null: only drivers' tickets carry one. */
    record TicketClaims(UUID userId, Set<UserRole> roles, String cityId) {

        public TicketClaims {
            roles = Set.copyOf(roles);
        }
    }
}

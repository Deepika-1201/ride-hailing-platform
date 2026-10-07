package com.ridehailing.identity.tickets;

import com.ridehailing.shared.UserRole;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Single-use WebSocket tickets (LLD §14.1): a ticket stands for its claims for 60 s by the application clock, and
 * only the first redemption gets them. Kept in Valkey when the stores live there, else in process memory.
 */
public interface TicketStore {

    Duration TTL = Duration.ofSeconds(60);

    Ticket issue(TicketClaims claims);

    /** The ticket's claims, once: a second redemption, or one after it expired, finds nothing. */
    Optional<TicketClaims> redeem(String ticket);

    /** {@code cityId} may be null: riders have none. */
    record TicketClaims(UUID userId, Set<UserRole> roles, String cityId) {

        public TicketClaims {
            roles = Set.copyOf(roles);
        }
    }

    record Ticket(String value, Instant expiresAt) {
    }

    /** 32 random bytes, base64url without padding. */
    final class Values {

        private static final SecureRandom RANDOM = new SecureRandom();

        private Values() {
        }

        static String next() {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
    }
}

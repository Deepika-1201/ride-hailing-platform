package com.ridehailing.identity.tickets;

import com.ridehailing.platform.Valkey;
import com.ridehailing.shared.UserRole;
import io.lettuce.core.SetArgs;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Tickets in Valkey (LLD §14.1): {@code wsticket:<ticket>} holds the user, roles, city and expiry, separated by
 * {@code |}, for 60 s, and redemption takes it with {@code GETDEL}, so it works once on any node.
 */
@Component
@ConditionalOnProperty(name = "ride.location.store", havingValue = "valkey")
class ValkeyTicketStore implements TicketStore {

    private static final String OPERATION = "ticket";

    private final Valkey valkey;
    private final Clock clock;

    ValkeyTicketStore(Valkey valkey, Clock clock) {
        this.valkey = valkey;
        this.clock = clock;
    }

    @Override
    public Ticket issue(TicketClaims claims) {
        Ticket ticket = new Ticket(Values.next(), Instant.ofEpochMilli(clock.millis()).plus(TTL));
        String value = String.join("|", claims.userId().toString(),
                claims.roles().stream().map(UserRole::name).sorted().collect(Collectors.joining(",")),
                claims.cityId() == null ? "" : claims.cityId(), Long.toString(ticket.expiresAt().toEpochMilli()));
        valkey.await(OPERATION, valkey.timeouts().other(),
                valkey.commands().set(key(ticket.value()), value, SetArgs.Builder.ex(TTL)));
        return ticket;
    }

    @Override
    public Optional<TicketClaims> redeem(String ticket) {
        String value = valkey.await(OPERATION, valkey.timeouts().other(), valkey.commands().getdel(key(ticket)));
        if (value == null) {
            return Optional.empty();
        }
        String[] fields = value.split("\\|", -1);
        if (clock.millis() >= Long.parseLong(fields[3])) {
            return Optional.empty();
        }
        Set<UserRole> roles = fields[1].isEmpty() ? Set.of()
                : Arrays.stream(fields[1].split(",")).map(UserRole::valueOf).collect(Collectors.toSet());
        return Optional.of(new TicketClaims(UUID.fromString(fields[0]), roles, fields[2].isEmpty() ? null
                : fields[2]));
    }

    private static String key(String ticket) {
        return "wsticket:" + ticket;
    }
}

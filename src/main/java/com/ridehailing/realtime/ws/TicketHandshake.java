package com.ridehailing.realtime.ws;

import com.ridehailing.identity.IdentityApi;
import com.ridehailing.identity.IdentityApi.TicketClaims;
import com.ridehailing.platform.ConditionalOnRole;
import com.ridehailing.platform.Role;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Redeems the handshake's ticket before the upgrade (LLD §14.1, §14.7): a missing, unknown, used or expired ticket
 * gets {@code 401}; a draining node, or one that can't reach the ticket store, {@code 503}.
 */
@Component
@ConditionalOnRole(Role.REALTIME)
class TicketHandshake implements HandshakeInterceptor {

    static final String CLAIMS = "ride.claims";

    private static final Logger log = LoggerFactory.getLogger(TicketHandshake.class);

    private final IdentityApi identity;
    private final Drain drain;

    TicketHandshake(IdentityApi identity, Drain drain) {
        this.identity = identity;
        this.drain = drain;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            Map<String, Object> attributes) {
        if (drain.draining()) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
        String ticket = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");
        Optional<TicketClaims> claims;
        try {
            claims = ticket == null ? Optional.empty() : identity.redeemTicket(ticket);
        } catch (RuntimeException e) {
            log.warn("Redeeming a WebSocket ticket failed", e);
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
        if (claims.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(CLAIMS, claims.get());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            Exception failure) {
    }
}

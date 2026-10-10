package com.ridehailing.realtime.web;

import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.identity.IdentityApi;
import com.ridehailing.identity.IdentityApi.RealtimeTicket;
import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.RateLimiter;
import com.ridehailing.realtime.RealtimeProperties;
import com.ridehailing.shared.UserRole;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;

/** WebSocket tickets (LLD §14.1, §14.7); only a driver's ticket carries a city, from dispatch. */
@ApiController
class TicketController {

    static final String LIMIT = "tickets-per-user";

    private final IdentityApi identity;
    private final DispatchApi dispatch;
    private final RateLimiter rateLimiter;
    private final RealtimeProperties properties;

    TicketController(IdentityApi identity, DispatchApi dispatch, RateLimiter rateLimiter,
            RealtimeProperties properties) {
        this.identity = identity;
        this.dispatch = dispatch;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @PostMapping("/v1/realtime/tickets")
    @AllowedRoles({UserRole.RIDER, UserRole.DRIVER, UserRole.OPS})
    ResponseEntity<TicketView> issue(Caller caller) {
        rateLimiter.acquireOrReject(LIMIT, caller.userId().toString());
        String cityId = caller.roles().contains(UserRole.DRIVER) ? dispatch.status(caller.userId()).cityId() : null;
        RealtimeTicket ticket = identity.issueTicket(caller.userId(), caller.roles(), cityId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new TicketView(ticket.ticket(), ticket.expiresAt(), properties.url()));
    }

    /** The RealtimeTicket schema of {@code openapi.yaml}. */
    record TicketView(String ticket, Instant expiresAt, URI url) {
    }
}

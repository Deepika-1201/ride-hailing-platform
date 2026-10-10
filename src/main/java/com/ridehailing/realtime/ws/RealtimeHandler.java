package com.ridehailing.realtime.ws;

import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.identity.IdentityApi.TicketClaims;
import com.ridehailing.location.LiveIndex.LocationUpdate;
import com.ridehailing.platform.ConditionalOnRole;
import com.ridehailing.platform.PushBus;
import com.ridehailing.platform.RateLimiter;
import com.ridehailing.platform.Role;
import com.ridehailing.realtime.RealtimeProperties;
import com.ridehailing.ride.RideQueries;
import com.ridehailing.ride.RideStatus;
import com.ridehailing.shared.UserRole;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code /ws} endpoint (LLD §14): subscribes each session to its channels, and handles what clients send. Binary
 * frames close the session with 1003 here; the container closes it with 1009 for a frame over 1 KB.
 */
@Component
@ConditionalOnRole(Role.REALTIME)
class RealtimeHandler extends TextWebSocketHandler {

    static final String LOCATION_LIMIT = "location-per-driver";

    private static final Logger log = LoggerFactory.getLogger(RealtimeHandler.class);
    /** Tomcat's per-session bound on a blocking send. */
    private static final String SEND_TIMEOUT = "org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT";
    private static final Set<RideStatus> RIDING = EnumSet.of(RideStatus.DRIVER_ASSIGNED, RideStatus.DRIVER_ARRIVED,
            RideStatus.IN_TRIP);

    private final LiveSessions sessions;
    private final Tracking tracking;
    private final PushBus push;
    private final RideQueries rides;
    private final DispatchApi dispatch;
    private final RateLimiter rateLimiter;
    private final JsonMapper json;
    private final MeterRegistry meters;
    private final RealtimeProperties properties;

    RealtimeHandler(LiveSessions sessions, Tracking tracking, PushBus push, RideQueries rides, DispatchApi dispatch,
            RateLimiter rateLimiter, JsonMapper json, MeterRegistry meters, RealtimeProperties properties) {
        this.sessions = sessions;
        this.tracking = tracking;
        this.push = push;
        this.rides = rides;
        this.dispatch = dispatch;
        this.rateLimiter = rateLimiter;
        this.json = json;
        this.meters = meters;
        this.properties = properties;
    }

    /** Drivers hear on {@code drv:}, riders on {@code rdr:} and on the ride channel of their ride under way. */
    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        boundSends(socket);
        Session session = new Session(socket, (TicketClaims) socket.getAttributes().get(TicketHandshake.CLAIMS), json,
                properties.bufferLimit().toBytes());
        sessions.add(session);
        try {
            if (session.has(UserRole.DRIVER)) {
                session.subscribe(push, PushBus.driverChannel(session.userId()), session::relay);
            }
            if (session.has(UserRole.RIDER)) {
                session.subscribe(push, PushBus.riderChannel(session.userId()), message -> toRider(session, message));
                session.change(() -> rides.activeRideOfRider(session.userId())
                        .filter(ride -> RIDING.contains(ride.status()))
                        .ifPresent(ride -> session.follow(push, ride.id())));
            }
        } catch (RuntimeException e) {
            log.warn("Subscribing session {} failed; closing it", session.id(), e);
            session.close(CloseStatus.SERVER_ERROR, "error");
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage text) {
        Session session = sessions.get(socket.getId());
        if (session == null) {
            return;
        }
        session.heard();
        JsonNode message;
        try {
            message = json.readTree(text.getPayload());
        } catch (JacksonException e) {
            session.error("INVALID_MESSAGE", "The message isn't JSON.");
            return;
        }
        JsonNode type = message.get("type");
        switch (type != null && type.isString() ? type.asString() : "") {
            case "location" -> location(session, message);
            case "offer_seen" -> offerSeen(session, message);
            case "ops_viewport" -> viewport(session, message);
            default -> session.error("INVALID_MESSAGE", "The message has no type this server knows.");
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession socket, PongMessage message) {
        Session session = sessions.get(socket.getId());
        if (session != null) {
            session.heard();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        sessions.closed(socket.getId());
    }

    private void location(Session session, JsonNode message) {
        if (!session.has(UserRole.DRIVER)) {
            session.error("FORBIDDEN", "Only drivers send locations.");
            return;
        }
        LocationUpdate update = Inbound.location(message);
        if (update == null) {
            session.error("INVALID_MESSAGE", "A location needs seq, lat, lon, accuracy_m and device_time in range.");
            return;
        }
        if (!rateLimiter.tryAcquire(LOCATION_LIMIT, session.userId().toString()).allowed()) {
            meters.counter("location.updates", "result", "rate_limited").increment();
            session.error("RATE_LIMITED", "Send at most one location a second.");
            return;
        }
        tracking.location(session, update);
    }

    private void offerSeen(Session session, JsonNode message) {
        if (!session.has(UserRole.DRIVER)) {
            session.error("FORBIDDEN", "Only drivers see offers.");
            return;
        }
        UUID offerId = Inbound.uuid(message, "offer_id");
        if (offerId == null) {
            session.error("INVALID_MESSAGE", "offer_seen needs an offer_id.");
            return;
        }
        dispatch.offerSeen(session.userId(), offerId);
    }

    private void viewport(Session session, JsonNode message) {
        if (!session.has(UserRole.OPS)) {
            session.error("FORBIDDEN", "Only operations stream the map.");
            return;
        }
        Session.Viewport viewport = Inbound.viewport(message);
        if (viewport == null) {
            session.error("INVALID_MESSAGE", "ops_viewport needs a city_id and a bbox with its minimums first.");
            return;
        }
        session.viewport(viewport);
    }

    /** Relays, and follows the ride's channel while a driver is on the way or driving (LLD §14.3). */
    private void toRider(Session session, String message) {
        session.relay(message);
        JsonNode push = json.readTree(message);
        JsonNode type = push.get("type");
        if (type == null || !"ride_status".equals(type.asString())) {
            return;
        }
        UUID rideId = UUID.fromString(push.get("ride_id").asString());
        if (RIDING.contains(RideStatus.valueOf(push.get("status").asString()))) {
            session.change(() -> session.follow(this.push, rideId));
        } else {
            session.change(() -> session.unfollow(rideId));
        }
    }

    private void boundSends(WebSocketSession socket) {
        if (socket instanceof NativeWebSocketSession nativeSession) {
            jakarta.websocket.Session container = nativeSession.getNativeSession(jakarta.websocket.Session.class);
            if (container != null) {
                container.getUserProperties().put(SEND_TIMEOUT, properties.sendTimeLimit().toMillis());
            }
        }
    }
}

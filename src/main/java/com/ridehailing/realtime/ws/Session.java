package com.ridehailing.realtime.ws;

import com.ridehailing.identity.IdentityApi.TicketClaims;
import com.ridehailing.platform.PushBus;
import com.ridehailing.platform.PushBus.Subscription;
import com.ridehailing.shared.BoundingBox;
import com.ridehailing.shared.UserRole;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/**
 * One connection (LLD §14.7): who it is, its subscriptions, and its outbox. Subscriptions change on a virtual thread
 * of the session's own, since pushes arrive on threads that mustn't wait for Valkey.
 */
final class Session {

    enum Kind {
        DRIVER,
        RIDER,
        OPS;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    record Viewport(String cityId, BoundingBox box) {
    }

    /** Where dispatch has the driver online, and when this session read it. */
    record Place(String cityId, String category, long readAt) {
    }

    private static final Logger log = LoggerFactory.getLogger(Session.class);

    private final WebSocketSession socket;
    private final TicketClaims claims;
    private final JsonMapper json;
    private final Outbox outbox;
    private final ExecutorService changes;
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final Map<UUID, Subscription> followed = new ConcurrentHashMap<>();
    private volatile long heardAt = System.nanoTime();
    private volatile long pingedAt = System.nanoTime();
    private volatile String closeReason;
    private volatile boolean released;
    private volatile Viewport viewport;
    private volatile Place place;

    Session(WebSocketSession socket, TicketClaims claims, JsonMapper json, long bufferLimit) {
        this.socket = socket;
        this.claims = claims;
        this.json = json;
        this.outbox = new Outbox("ws-out-" + socket.getId(), socket::sendMessage, bufferLimit,
                status -> close(status, "slow"));
        this.changes = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("ws-changes-" + socket.getId())
                .factory());
    }

    String id() {
        return socket.getId();
    }

    UUID userId() {
        return claims.userId();
    }

    boolean has(UserRole role) {
        return claims.roles().contains(role);
    }

    Kind kind() {
        return has(UserRole.OPS) ? Kind.OPS : has(UserRole.DRIVER) ? Kind.DRIVER : Kind.RIDER;
    }

    void send(Object message) {
        outbox.send(json.writeValueAsString(message));
    }

    void relay(String message) {
        outbox.send(message);
    }

    /** A newer message with the key replaces one still waiting, as positions do (LLD §14.4). */
    void relayLatest(String key, String message) {
        outbox.sendLatest(key, message);
    }

    void error(String code, String message) {
        send(Messages.ErrorMessage.of(code, message));
    }

    void ping() {
        pingedAt = System.nanoTime();
        outbox.ping();
    }

    void heard() {
        heardAt = System.nanoTime();
    }

    long heardAt() {
        return heardAt;
    }

    long pingedAt() {
        return pingedAt;
    }

    void subscribe(PushBus push, String channel, Consumer<String> receiver) {
        subscriptions.add(push.subscribe(channel, receiver));
    }

    /** Runs a subscription change after the ones before it; none run once the session is released. */
    void change(Runnable change) {
        try {
            changes.execute(() -> {
                try {
                    change.run();
                } catch (RuntimeException e) {
                    log.warn("A subscription change of session {} failed; closing it", id(), e);
                    close(CloseStatus.SERVER_ERROR, "error");
                }
            });
        } catch (RejectedExecutionException e) {
            log.debug("Session {} is released; dropping a subscription change", id());
        }
    }

    /** The rider's ride channel, from assignment until the ride ends (LLD §14.3). */
    void follow(PushBus push, UUID rideId) {
        if (released) {
            return;
        }
        followed.computeIfAbsent(rideId, ride -> push.subscribe(PushBus.rideChannel(ride),
                message -> relayLatest("ride:" + ride, message)));
        if (released) {
            unfollow(rideId);
        }
    }

    void unfollow(UUID rideId) {
        Subscription subscription = followed.remove(rideId);
        if (subscription != null) {
            subscription.close();
        }
    }

    Viewport viewport() {
        return viewport;
    }

    void viewport(Viewport viewport) {
        this.viewport = viewport;
    }

    Place place() {
        return place;
    }

    void place(Place place) {
        this.place = place;
    }

    /** Closes the connection unless the client did first; the reason is counted when it closes. */
    void close(CloseStatus status, String reason) {
        if (closeReason == null) {
            closeReason = reason;
        }
        try {
            socket.close(status);
        } catch (IOException e) {
            log.debug("Closing session {} failed", id(), e);
        }
    }

    /** {@code client} unless this side closed it. */
    String closeReason() {
        return closeReason == null ? "client" : closeReason;
    }

    /** After the connection closed: its subscriptions and threads go. */
    void release() {
        released = true;
        outbox.end();
        changes.shutdownNow();
        subscriptions.forEach(Subscription::close);
        followed.keySet().forEach(this::unfollow);
    }
}

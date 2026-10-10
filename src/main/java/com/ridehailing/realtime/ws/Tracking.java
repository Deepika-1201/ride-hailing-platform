package com.ridehailing.realtime.ws;

import com.ridehailing.dispatch.AvailabilityStatus;
import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.dispatch.DispatchApi.DriverStatusView;
import com.ridehailing.geography.GeographyApi;
import com.ridehailing.geography.GeographyApi.CityView;
import com.ridehailing.geography.RoutingProvider;
import com.ridehailing.geography.RoutingProvider.Route;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.LocationUpdate;
import com.ridehailing.location.LiveIndex.UpdateResult;
import com.ridehailing.location.LocationIngestion;
import com.ridehailing.notification.NotificationApi;
import com.ridehailing.platform.ConditionalOnRole;
import com.ridehailing.platform.PushBus;
import com.ridehailing.platform.Role;
import com.ridehailing.realtime.RealtimeProperties;
import com.ridehailing.ride.RideQueries;
import com.ridehailing.shared.GeoPoint;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A driver's live locations from their session (LLD §9.6, §9.7): into the ingestion with the city and category
 * dispatch has for them, and each applied position of a driver on a ride out to the ride's channel with an ETA, which
 * this node recomputes every {@code eta-every}. The first pickup ETA within {@code arriving-within} tells the rider.
 */
@Component
@ConditionalOnRole(Role.REALTIME)
class Tracking {

    private static final long PLACE_FOR_NANOS = Duration.ofSeconds(30).toNanos();
    private static final int REMEMBERED_RIDES = 10_000;

    private final LocationIngestion ingestion;
    private final DispatchApi dispatch;
    private final RideQueries rides;
    private final GeographyApi geography;
    private final RoutingProvider routing;
    private final NotificationApi notifications;
    private final PushBus push;
    private final Clock clock;
    private final long etaEveryNanos;
    private final long arrivingWithinS;
    private final Map<UUID, Ends> ends = remembered();
    private final Map<UUID, Eta> etas = remembered();
    private final Map<UUID, Boolean> arriving = remembered();

    Tracking(LocationIngestion ingestion, DispatchApi dispatch, RideQueries rides, GeographyApi geography,
            RoutingProvider routing, NotificationApi notifications, PushBus push, Clock clock,
            RealtimeProperties properties) {
        this.ingestion = ingestion;
        this.dispatch = dispatch;
        this.rides = rides;
        this.geography = geography;
        this.routing = routing;
        this.notifications = notifications;
        this.push = push;
        this.clock = clock;
        this.etaEveryNanos = properties.etaEvery().toNanos();
        this.arrivingWithinS = properties.arrivingWithin().toSeconds();
    }

    /** An update from a driver who isn't online is ignored, as over HTTPS. */
    void location(Session session, LocationUpdate update) {
        Session.Place place = place(session);
        if (place == null) {
            return;
        }
        UpdateResult result = ingestion.live(place.cityId(), place.category(), session.userId(), update)
                .orElse(null);
        if (result == null) {
            return;
        }
        if (result.outcome() == UpdateResult.Outcome.OFFLINE) {
            session.place(null);
            return;
        }
        boolean onARide = result.status() == LiveIndex.Status.ASSIGNED || result.status() == LiveIndex.Status.ON_TRIP;
        if (result.outcome() == UpdateResult.Outcome.APPLIED && result.flags() == 0 && onARide
                && result.rideId() != null) {
            Integer etaS = eta(result.rideId(), result.status() == LiveIndex.Status.ASSIGNED, update.position(),
                    place.cityId());
            push.publish(PushBus.rideChannel(result.rideId()), new Messages.DriverPosition(result.rideId(),
                    update.position().lat(), update.position().lon(), update.headingDeg(), update.seq(), etaS));
        }
    }

    /** Read again after 30 s, and whenever the last read found the driver offline. */
    private Session.Place place(Session session) {
        Session.Place place = session.place();
        if (place != null && System.nanoTime() - place.readAt() < PLACE_FOR_NANOS) {
            return place;
        }
        DriverStatusView status = dispatch.status(session.userId());
        place = status.status() == AvailabilityStatus.OFFLINE ? null
                : new Session.Place(status.cityId(), status.category(), System.nanoTime());
        session.place(place);
        return place;
    }

    private Integer eta(UUID rideId, boolean toPickup, GeoPoint from, String cityId) {
        Ends ride = ends.get(rideId);
        if (ride == null) {
            ride = rides.find(rideId).map(found -> new Ends(found.riderId(), found.pickup(), found.dropoff()))
                    .orElse(null);
            if (ride == null) {
                return null;
            }
            ends.put(rideId, ride);
        }
        long now = System.nanoTime();
        Eta eta = etas.get(rideId);
        if (eta == null || eta.toPickup() != toPickup || now - eta.at() >= etaEveryNanos) {
            Integer seconds = routing.route(from, toPickup ? ride.pickup() : ride.dropoff(), departure(cityId))
                    .map(Route::durationS).orElse(null);
            eta = new Eta(toPickup, seconds, now);
            etas.put(rideId, eta);
            if (toPickup && seconds != null && seconds <= arrivingWithinS && arriving.putIfAbsent(rideId, true) == null) {
                notifications.driverArriving(rideId, ride.riderId());
            }
        }
        return eta.seconds();
    }

    private ZonedDateTime departure(String cityId) {
        return ZonedDateTime.ofInstant(clock.instant(),
                geography.city(cityId).map(CityView::timeZone).orElse(ZoneOffset.UTC));
    }

    /** The most recently used entries, so rides that ended age out. */
    private static <V> Map<UUID, V> remembered() {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, V> eldest) {
                return size() > REMEMBERED_RIDES;
            }
        });
    }

    /** A ride's ends never change, so each node reads them once. */
    private record Ends(UUID riderId, GeoPoint pickup, GeoPoint dropoff) {
    }

    private record Eta(boolean toPickup, Integer seconds, long at) {
    }
}

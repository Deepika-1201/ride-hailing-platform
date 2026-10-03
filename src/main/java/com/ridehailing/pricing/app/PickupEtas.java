package com.ridehailing.pricing.app;

import com.ridehailing.geography.GeographyApi;
import com.ridehailing.geography.RoutingProvider;
import com.ridehailing.geography.RoutingProvider.Route;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.shared.GeoPoint;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * How long the nearest available driver would take to reach the pickup (LLD §10.4). Without a live index (before
 * phase 6) or a driver within the category's maximum radius, there is no estimate.
 */
@Component
class PickupEtas {

    private final ObjectProvider<LiveIndex> liveIndex;
    private final GeographyApi geography;
    private final RoutingProvider routing;

    PickupEtas(ObjectProvider<LiveIndex> liveIndex, GeographyApi geography, RoutingProvider routing) {
        this.liveIndex = liveIndex;
        this.geography = geography;
        this.routing = routing;
    }

    Optional<Integer> estimate(String cityId, String category, GeoPoint pickup, ZonedDateTime now) {
        LiveIndex index = liveIndex.getIfAvailable();
        if (index == null) {
            return Optional.empty();
        }
        return geography.settings(cityId, category)
                .flatMap(settings -> index.nearby(cityId, category, pickup, settings.radiusMaxM(), 1).stream()
                        .findFirst())
                .flatMap(nearest -> routing.route(nearest.position(), pickup, now))
                .map(Route::durationS);
    }
}

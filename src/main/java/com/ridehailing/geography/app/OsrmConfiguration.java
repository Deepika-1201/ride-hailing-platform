package com.ridehailing.geography.app;

import com.ridehailing.geography.GeographyApi;
import com.ridehailing.geography.RoutingProvider;
import com.ridehailing.platform.Valkey;
import com.ridehailing.shared.GeoPoint;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ride.routing.osrm", name = "enabled", havingValue = "true")
class OsrmConfiguration {

    @Bean(destroyMethod = "close")
    HttpClient osrmHttpClient(OsrmProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.timeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
    RoutingProvider osrmRoutingProvider(GeographyApi geography, OsrmProperties properties,
            @Qualifier("osrmHttpClient") HttpClient http, JsonMapper json, ObjectProvider<Valkey> valkey,
            H3Zones zones, MeterRegistry metrics) {
        MockRoutingProvider mock = new MockRoutingProvider();
        OsrmRoutingProvider osrm = new OsrmRoutingProvider(properties.baseUrl(), properties.timeout(), http, json, mock, metrics);
        CachedRoutingProvider cached = new CachedRoutingProvider(osrm, valkey.getIfAvailable(), json, zones, properties.baseUrl(),
                properties.cacheTtl(), properties.cacheTimeout(), metrics);
        return new RoutingProvider() {
            @Override
            public Optional<Route> route(GeoPoint from, GeoPoint to, ZonedDateTime departure) {
                return geography.locate(from).filter(location -> properties.cities().contains(location.cityId()))
                        .map(location -> cached.route(from, to, departure)).orElseGet(() -> mock.route(from, to, departure));
            }

            @Override
            public Optional<DurationMatrix> matrix(String cityId, List<GeoPoint> origins, List<GeoPoint> destinations,
                    ZonedDateTime departure) {
                return properties.cities().contains(cityId) ? osrm.matrix(cityId, origins, destinations, departure) : Optional.empty();
            }
        };
    }
}
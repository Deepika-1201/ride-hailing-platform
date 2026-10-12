package com.ridehailing.geography.app;

import com.ridehailing.geography.GeographyApi;
import com.ridehailing.geography.RoutingProvider;
import com.ridehailing.platform.Valkey;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
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
        return (from, to, departure) -> geography.locate(from)
                .filter(location -> properties.cities().contains(location.cityId()))
                .map(location -> cached.route(from, to, departure))
                .orElseGet(() -> mock.route(from, to, departure));
    }
}
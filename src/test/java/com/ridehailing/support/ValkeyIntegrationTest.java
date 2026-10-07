package com.ridehailing.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The application with its stores in the shared Valkey node (LLD §1.3, §17.1), on a database of its own: after
 * Valkey loses its data, the watch repairs every city with online drivers, and the shared database holds every city
 * other classes left online. Timeouts are generous, so a slow runner doesn't fail a test that isn't about them.
 */
@TestPropertySource(properties = {
    "ride.location.store=valkey",
    "ride.valkey.timeouts.query=5s",
    "ride.valkey.timeouts.mirror=5s",
    "ride.valkey.timeouts.update=5s",
    "ride.valkey.timeouts.other=5s"
})
public abstract class ValkeyIntegrationTest extends IntegrationTest {

    @DynamicPropertySource
    static void valkey(DynamicPropertyRegistry registry) {
        registry.add("ride.valkey.uri", Valkeys::uri);
        // Bound onto the pool after spring.datasource.url, so it replaces the shared database.
        registry.add("spring.datasource.hikari.jdbc-url", () -> Postgis.database("valkey"));
    }
}

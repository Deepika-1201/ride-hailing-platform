package com.ridehailing;

import com.ridehailing.support.Postgis;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs the application against a throwaway PostGIS container, without Compose: {@code ./gradlew bootTestRun}. */
public final class TestRideHailingApplication {

    private TestRideHailingApplication() {
    }

    public static void main(String[] args) {
        SpringApplication.from(RideHailingApplication::main)
                .with(LocalDatabase.class)
                .withAdditionalProfiles("local")
                .run(args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalDatabase {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return Postgis.newContainer();
        }
    }
}

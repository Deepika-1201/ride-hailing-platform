package com.ridehailing.support;

import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestUsers.TestUser;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Verified drivers with one active vehicle, created straight in the database, with real access tokens. */
@TestComponent
public class TestDrivers {

    /** Plates are unique; counted like phones in {@link TestUsers#newPhone()}. */
    private static final AtomicInteger PLATES = new AtomicInteger();

    private final JdbcClient jdbc;
    private final TestUsers users;

    TestDrivers(JdbcClient jdbc, TestUsers users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    public TestDriver create(TestCity city, String category) {
        TestUser user = users.create(UserRole.DRIVER);
        jdbc.sql("""
                        INSERT INTO driver.drivers (id, city_id, first_name, verification)
                        VALUES (:id, :cityId, 'Test', 'VERIFIED')
                        """)
                .param("id", user.id())
                .param("cityId", city.id())
                .update();
        return new TestDriver(user.id(), user.authorization(), city, category, addVehicle(user.id(), category));
    }

    public UUID addVehicle(UUID driverId, String category) {
        UUID id = Ids.newId();
        jdbc.sql("""
                        INSERT INTO driver.vehicles (id, driver_id, category, plate, make, model, colour)
                        VALUES (:id, :driverId, :category, :plate, 'Maruti', 'Swift', 'White')
                        """)
                .param("id", id)
                .param("driverId", driverId)
                .param("category", category)
                .param("plate", "T " + "%09d".formatted(PLATES.incrementAndGet()))
                .update();
        return id;
    }

    public record TestDriver(UUID id, String authorization, TestCity city, String category, UUID vehicleId) {
    }
}

package com.ridehailing.driver.db;

import com.ridehailing.driver.DriverApi.Vehicle;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

/** Vehicles; plates are unique across drivers (LLD §4.3). */
@Repository
public class VehicleRepository {

    private static final String COLUMNS = """
            SELECT id, driver_id, category, plate, make, model, colour, active, version
            FROM driver.vehicles
            """;

    private final JdbcClient jdbc;

    VehicleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** False if the plate is taken. */
    public boolean insert(Vehicle vehicle) {
        return jdbc.sql("""
                        INSERT INTO driver.vehicles (id, driver_id, category, plate, make, model, colour)
                        VALUES (:id, :driverId, :category, :plate, :make, :model, :colour)
                        ON CONFLICT (plate) DO NOTHING
                        """)
                .param("id", vehicle.id())
                .param("driverId", vehicle.driverId())
                .param("category", vehicle.category())
                .param("plate", vehicle.plate())
                .param("make", vehicle.make())
                .param("model", vehicle.model())
                .param("colour", vehicle.colour())
                .update() == 1;
    }

    public Optional<Vehicle> find(UUID id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id").param("id", id).query(VehicleRepository::vehicle).optional();
    }

    /** Oldest first. */
    public List<Vehicle> ofDriver(UUID driverId) {
        return jdbc.sql(COLUMNS + "WHERE driver_id = :driverId ORDER BY created_at, id")
                .param("driverId", driverId)
                .query(VehicleRepository::vehicle)
                .list();
    }

    /** Each driver's vehicles, oldest first; drivers without vehicles are absent. */
    public Map<UUID, List<Vehicle>> ofDrivers(Collection<UUID> driverIds) {
        return jdbc.sql(COLUMNS + "WHERE driver_id = ANY(:driverIds) ORDER BY created_at, id")
                .param("driverIds", new SqlArrayValue("uuid", driverIds.toArray()))
                .query(VehicleRepository::vehicle)
                .stream()
                .collect(Collectors.groupingBy(Vehicle::driverId));
    }

    /** Applies the change only at the expected version. */
    public Optional<Vehicle> setActive(UUID id, boolean active, int expectedVersion) {
        return jdbc.sql("""
                        UPDATE driver.vehicles SET active = :active, version = version + 1
                        WHERE id = :id AND version = :version
                        RETURNING id, driver_id, category, plate, make, model, colour, active, version
                        """)
                .param("active", active)
                .param("id", id)
                .param("version", expectedVersion)
                .query(VehicleRepository::vehicle)
                .optional();
    }

    private static Vehicle vehicle(ResultSet row, int rowNumber) throws SQLException {
        return new Vehicle(row.getObject("id", UUID.class), row.getObject("driver_id", UUID.class),
                row.getString("category"), row.getString("plate"), row.getString("make"), row.getString("model"),
                row.getString("colour"), row.getBoolean("active"), row.getInt("version"));
    }
}

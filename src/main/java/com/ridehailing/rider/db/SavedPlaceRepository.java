package com.ridehailing.rider.db;

import com.ridehailing.shared.GeoPoint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** A rider's saved places; labels are unique per rider (LLD §4.3). */
@Repository
public class SavedPlaceRepository {

    private static final String COLUMNS = "SELECT id, label, name, lat, lon, created_at FROM rider.saved_places ";

    private final JdbcClient jdbc;

    SavedPlaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int count(UUID riderId) {
        return jdbc.sql("SELECT count(*) FROM rider.saved_places WHERE rider_id = :riderId")
                .param("riderId", riderId)
                .query(Integer.class)
                .single();
    }

    /** False if the rider has a place with this label already. */
    public boolean insert(UUID id, UUID riderId, String label, String name, GeoPoint location) {
        return jdbc.sql("""
                        INSERT INTO rider.saved_places (id, rider_id, label, name, lat, lon)
                        VALUES (:id, :riderId, :label, :name, :lat, :lon)
                        ON CONFLICT (rider_id, label) DO NOTHING
                        """)
                .param("id", id)
                .param("riderId", riderId)
                .param("label", label)
                .param("name", name)
                .param("lat", location.lat())
                .param("lon", location.lon())
                .update() == 1;
    }

    /** Oldest first. */
    public List<SavedPlace> list(UUID riderId) {
        return jdbc.sql(COLUMNS + "WHERE rider_id = :riderId ORDER BY created_at, id")
                .param("riderId", riderId)
                .query(SavedPlaceRepository::place)
                .list();
    }

    public SavedPlace get(UUID id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id").param("id", id).query(SavedPlaceRepository::place).single();
    }

    /** False if the rider has no such place. */
    public boolean delete(UUID riderId, UUID id) {
        return jdbc.sql("DELETE FROM rider.saved_places WHERE id = :id AND rider_id = :riderId")
                .param("id", id)
                .param("riderId", riderId)
                .update() == 1;
    }

    private static SavedPlace place(ResultSet row, int rowNumber) throws SQLException {
        return new SavedPlace(row.getObject("id", UUID.class), row.getString("label"), row.getString("name"),
                new GeoPoint(row.getDouble("lat"), row.getDouble("lon")),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    public record SavedPlace(UUID id, String label, String name, GeoPoint location, Instant createdAt) {
    }
}

package com.ridehailing.ride.db;

import com.ridehailing.ride.RideQueries.TransitionView;
import com.ridehailing.ride.RideStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The transition log: one row per ride version after booking (LLD §4.5). */
@Repository
public class TransitionRepository {

    private final JdbcClient jdbc;

    TransitionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code from} is null for the booking; {@code actorId}, {@code reason} and {@code deviceTime} may be null. */
    public void insert(UUID id, UUID rideId, int version, RideStatus from, RideStatus to, String command,
            String actorType, String actorId, String reason, String requestId, Instant deviceTime) {
        jdbc.sql("""
                        INSERT INTO ride.transitions (id, ride_id, version, from_status, to_status, command, actor_type,
                                                      actor_id, reason, occurred_at, request_id, device_time)
                        VALUES (:id, :rideId, :version, :from, :to, :command, :actorType, :actorId, :reason, now(),
                                :requestId, :deviceTime)
                        """)
                .param("id", id)
                .param("rideId", rideId)
                .param("version", version)
                .param("from", from == null ? null : from.name())
                .param("to", to.name())
                .param("command", command)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("reason", reason)
                .param("requestId", requestId)
                .param("deviceTime", deviceTime == null ? null : deviceTime.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** The commands the actor gave on the ride, oldest first. */
    public List<String> commandsBy(UUID rideId, String actorId) {
        return jdbc.sql("SELECT command FROM ride.transitions WHERE ride_id = :rideId AND actor_id = :actorId ORDER BY version")
                .param("rideId", rideId)
                .param("actorId", actorId)
                .query(String.class)
                .list();
    }

    /** The ride's transitions, oldest first. */
    public List<TransitionView> ofRide(UUID rideId) {
        return jdbc.sql("""
                        SELECT version, from_status, to_status, command, actor_type, actor_id, reason, occurred_at,
                               request_id
                        FROM ride.transitions WHERE ride_id = :rideId ORDER BY version
                        """)
                .param("rideId", rideId)
                .query((row, rowNumber) -> new TransitionView(row.getInt("version"), row.getString("from_status"),
                        row.getString("to_status"), row.getString("command"), row.getString("actor_type"),
                        row.getString("actor_id"), row.getString("reason"),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant(), row.getString("request_id")))
                .list();
    }

    /**
     * Transition rows that break I5, in the city or in all when {@code cityId} is null: not in the table, not starting
     * from the previous row's state, versions that aren't 0, 1, 2…, and rides whose row disagrees with their log.
     */
    public List<String> outsideTheTable(String cityId, List<Allowed> table) {
        return jdbc.sql("""
                        WITH allowed (from_status, to_status, command, actor_type) AS (
                            SELECT * FROM unnest(CAST(:froms AS text[]), CAST(:tos AS text[]),
                                                 CAST(:commands AS text[]), CAST(:actors AS text[]))),
                        logged AS (
                            SELECT t.*, lag(t.to_status) OVER w AS previous_status,
                                   row_number() OVER w - 1 AS expected_version
                            FROM ride.transitions t JOIN ride.rides r ON r.id = t.ride_id
                            WHERE CAST(:cityId AS text) IS NULL OR r.city_id = :cityId
                            WINDOW w AS (PARTITION BY t.ride_id ORDER BY t.version))
                        SELECT 'ride ' || ride_id || ' version ' || version || ': '
                               || coalesce(from_status, 'nothing') || ' to ' || to_status || ' by ' || command || ' ('
                               || actor_type || ') is not in the transition table'
                        FROM logged l
                        WHERE NOT EXISTS (SELECT 1 FROM allowed a
                                          WHERE a.from_status IS NOT DISTINCT FROM l.from_status
                                            AND a.to_status = l.to_status AND a.command = l.command
                                            AND a.actor_type = l.actor_type)
                        UNION ALL
                        SELECT 'ride ' || ride_id || ' version ' || version || ' starts from '
                               || coalesce(from_status, 'nothing') || ' after ' || coalesce(previous_status, 'nothing')
                        FROM logged WHERE from_status IS DISTINCT FROM previous_status
                        UNION ALL
                        SELECT 'ride ' || ride_id || ' has version ' || version || ' where ' || expected_version
                               || ' was expected'
                        FROM logged WHERE version <> expected_version
                        UNION ALL
                        SELECT 'ride ' || r.id || ' is ' || r.status || ' at version ' || r.version
                               || ' but its log ends at ' || coalesce(last.to_status || ' version ' || last.version, 'nothing')
                        FROM ride.rides r
                        LEFT JOIN LATERAL (SELECT to_status, version FROM ride.transitions
                                           WHERE ride_id = r.id ORDER BY version DESC LIMIT 1) last ON true
                        WHERE (CAST(:cityId AS text) IS NULL OR r.city_id = :cityId)
                          AND (last.version IS DISTINCT FROM r.version OR last.to_status IS DISTINCT FROM r.status)
                        """)
                .param("froms", table.stream().map(Allowed::from).toArray(String[]::new))
                .param("tos", table.stream().map(Allowed::to).toArray(String[]::new))
                .param("commands", table.stream().map(Allowed::command).toArray(String[]::new))
                .param("actors", table.stream().map(Allowed::actorType).toArray(String[]::new))
                .param("cityId", cityId)
                .query(String.class)
                .list();
    }

    /** One row of the transition table; {@code from} is null for the booking. */
    public record Allowed(String from, String to, String command, String actorType) {
    }
}

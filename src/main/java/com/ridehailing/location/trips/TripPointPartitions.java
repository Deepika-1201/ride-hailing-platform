package com.ridehailing.location.trips;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The daily partitions of {@code location.trip_points}, named {@code trip_points_yyyymmdd}, and its default partition
 * (LLD §9.8). Names and bounds are formatted from a {@link LocalDate}'s digits only, so building the DDL from them is
 * safe.
 */
@Repository
public class TripPointPartitions {

    private static final Pattern NAME = Pattern.compile("trip_points_(\\d{4})(\\d{2})(\\d{2})");

    private final JdbcClient jdbc;

    TripPointPartitions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<LocalDate> existing() {
        return jdbc.sql("""
                        SELECT c.relname FROM pg_catalog.pg_inherits i
                        JOIN pg_catalog.pg_class c ON c.oid = i.inhrelid
                        WHERE i.inhparent = 'location.trip_points'::regclass
                        """)
                .query(String.class)
                .list()
                .stream()
                .map(NAME::matcher)
                .filter(Matcher::matches)
                .map(name -> LocalDate.of(Integer.parseInt(name.group(1)), Integer.parseInt(name.group(2)),
                        Integer.parseInt(name.group(3))))
                .sorted()
                .toList();
    }

    /** Whether the default partition holds points of the day; PostgreSQL then refuses the day a partition. */
    public boolean defaultHolds(LocalDate day) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM location.trip_points_default WHERE received_day = :day)")
                .param("day", day)
                .query(Boolean.class)
                .single();
    }

    /** Must run in a transaction: the lock timeout keeps the DDL from queueing inserts behind a long transaction. */
    public void create(LocalDate day) {
        jdbc.sql("SET LOCAL lock_timeout = '2s'").update();
        jdbc.sql("CREATE TABLE IF NOT EXISTS location." + name(day) + " PARTITION OF location.trip_points"
                        + " FOR VALUES FROM ('" + day + "') TO ('" + day.plusDays(1) + "')")
                .update();
    }

    /** Must run in a transaction, like {@link #create}. */
    public void drop(LocalDate day) {
        jdbc.sql("SET LOCAL lock_timeout = '2s'").update();
        jdbc.sql("DROP TABLE IF EXISTS location." + name(day)).update();
    }

    /** Deletes the default partition's points of the days before {@code day}; answers how many. */
    public int deleteDefaultBefore(LocalDate day) {
        return jdbc.sql("DELETE FROM location.trip_points_default WHERE received_day < :day")
                .param("day", day)
                .update();
    }

    private static String name(LocalDate day) {
        Objects.requireNonNull(day, "day");
        return "trip_points_%04d%02d%02d".formatted(day.getYear(), day.getMonthValue(), day.getDayOfMonth());
    }
}

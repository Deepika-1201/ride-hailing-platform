package com.ridehailing.location.trips;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.Transactions;
import com.ridehailing.support.IntegrationTest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** LLD §9.8: daily partitions stay two days ahead, and points past their 90 days are dropped. */
class TripPointPartitionMaintenanceTests extends IntegrationTest {

    private static final LocalDate ANCIENT = LocalDate.of(2020, 1, 1);

    @Autowired
    private TripPointPartitionMaintenance maintenance;

    @Autowired
    private TripPointPartitions partitions;

    @Autowired
    private Transactions transactions;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcClient jdbc;

    private final UUID rideId = UUID.randomUUID();
    private LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    @AfterEach
    void restore() {
        jdbc.sql("DELETE FROM location.trip_points_default WHERE ride_id = :id").param("id", rideId).update();
        transactions.run(() -> partitions.drop(ANCIENT));
        transactions.run(() -> partitions.drop(today.minusDays(90)));
        transactions.run(() -> partitions.create(today.plusDays(2)));
    }

    @Test
    void todayAndTheNextTwoDaysArePartitioned() {
        maintenance.maintain();

        assertThat(partitions.existing()).contains(today, today.plusDays(1), today.plusDays(2));
    }

    @Test
    void aMissingDayIsCreatedAndOnlyDaysPastRetentionAreDropped() {
        transactions.run(() -> partitions.drop(today.plusDays(2)));
        transactions.run(() -> partitions.create(ANCIENT));
        transactions.run(() -> partitions.create(today.minusDays(90)));

        maintenance.maintain();

        assertThat(partitions.existing()).contains(today.plusDays(2), today.minusDays(90)).doesNotContain(ANCIENT);
    }

    @Test
    void theDefaultPartitionKeepsItsDaysAndLosesOnlyPointsPastRetention() {
        transactions.run(() -> partitions.drop(today.plusDays(2)));
        insert(today.minusDays(91), 1);
        insert(today.minusDays(90), 2);
        insert(today.plusDays(2), 3);

        maintenance.maintain();

        assertThat(partitions.existing()).as("its points are in the default partition").doesNotContain(today.plusDays(2));
        assertThat(jdbc.sql("SELECT received_day FROM location.trip_points_default WHERE ride_id = :id ORDER BY seq")
                .param("id", rideId).query(LocalDate.class).list())
                .containsExactly(today.minusDays(90), today.plusDays(2));
    }

    private void insert(LocalDate day, long seq) {
        jdbc.sql("""
                        INSERT INTO location.trip_points (received_day, ride_id, seq, driver_id, received_at, lat, lon)
                        VALUES (:day, :ride, :seq, :ride, CAST(:day AS timestamptz), 12.9, 77.6)
                        """)
                .param("day", day).param("ride", rideId).param("seq", seq)
                .update();
    }
}

package com.ridehailing.platform;

import static com.ridehailing.support.ScriptedTimerHandler.Kind.TEST_TIMER;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.support.Effects;
import com.ridehailing.support.Eventually;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.Postgis;
import com.ridehailing.support.ScriptedConsumer;
import com.ridehailing.support.ScriptedTimerHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The loops as they run in production: started with the application, each with its role in the logging context. On a
 * database of their own: the relay delivers in ID order, and the shared one holds every event other classes left
 * unpublished.
 */
@TestPropertySource(properties = "ride.workers.autostart=true")
@DirtiesContext
class BackgroundLoopsTests extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    /** Bound onto the pool after {@code spring.datasource.url}, so it replaces the shared database. */
    @DynamicPropertySource
    static void ownDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.jdbc-url", () -> Postgis.database("background_loops"));
    }

    @Autowired
    private Outbox outbox;

    @Autowired
    private Timers timers;

    @Autowired
    private ScriptedConsumer firstConsumer;

    @Autowired
    private ScriptedTimerHandler timerHandler;

    @Autowired
    private InstanceId instance;

    @Autowired
    private TransactionTemplate template;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        Effects.reset(jdbc);
        firstConsumer.reset();
        timerHandler.reset();
    }

    @Test
    void theRelayDeliversACommittedEventOnTheWorkerRole() {
        List<String> roles = new CopyOnWriteArrayList<>();
        firstConsumer.afterEffect(event -> roles.add(MDC.get(LogContext.ROLE)));
        UUID ride = UUID.randomUUID();

        LogContext.run(Map.of(LogContext.ROLE, "api"), () -> template.executeWithoutResult(status ->
                outbox.append(DomainEvent.of(ScriptedConsumer.EVENT_TYPE, 1, "ride", ride, 1, new Seen(ride)))));

        Eventually.within(PATIENCE, () -> assertThat(roles).containsExactly("worker"));
    }

    @Test
    void thePollerFiresADueTimerOnTheDispatchRole() {
        List<String> roles = new CopyOnWriteArrayList<>();
        timerHandler.afterEffect(timer -> roles.add(MDC.get(LogContext.ROLE)));
        UUID ride = UUID.randomUUID();

        template.executeWithoutResult(status ->
                timers.schedule(TEST_TIMER, ride, Instant.now().minus(Duration.ofHours(1)), Map.of()));

        Eventually.within(PATIENCE, () -> {
            assertThat(Effects.count(jdbc, ScriptedTimerHandler.SOURCE, ride.toString())).isEqualTo(1);
            assertThat(roles).containsExactly("dispatch");
        });
    }

    @Test
    void recurringJobsRanOnStartup() {
        Eventually.within(PATIENCE, () -> assertThat(jdbc.sql("""
                        SELECT name FROM platform.leases WHERE name LIKE 'job:%' AND holder = :me
                        """)
                .param("me", instance.value())
                .query(String.class)
                .list()).contains("job:platform-retention", "job:audit-partitions"));
    }

    record Seen(UUID rideId) {
    }
}

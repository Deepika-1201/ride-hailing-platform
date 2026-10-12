package com.ridehailing.pricing.app;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ridehailing.geography.GeographyApi;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.LocationUpdate;
import com.ridehailing.location.LiveIndex.MirrorState;
import com.ridehailing.location.LiveIndex.Status;
import com.ridehailing.platform.CityOwnership;
import com.ridehailing.platform.Leases;
import com.ridehailing.pricing.PricingApi;
import com.ridehailing.pricing.PricingApi.QuoteRequest;
import com.ridehailing.pricing.db.ComputedSurgeRepository;
import com.ridehailing.pricing.db.ComputedSurgeRepository.Demand;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.MutableClock;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestUsers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(ComputedSurgeRuntimeTests.ClockConfiguration.class)
@DirtiesContext
class ComputedSurgeRuntimeTests extends IntegrationTest {

    private static final String CITY = TestCities.newId();

    @DynamicPropertySource
    static void enableSurge(DynamicPropertyRegistry registry) {
        registry.add("ride.dispatch.city-ownership.enabled", () -> true);
        registry.add("ride.dispatch.city-ownership.cities", () -> CITY);
        registry.add("ride.pricing.computed-surge.enabled", () -> true);
        String prefix = "ride.pricing.computed-surge.cities." + CITY;
        registry.add(prefix + "[0].ratio-from", () -> 0);
        registry.add(prefix + "[0].multiplier", () -> 1);
        registry.add(prefix + "[1].ratio-from", () -> 1);
        registry.add(prefix + "[1].multiplier", () -> "1.5");
        registry.add(prefix + "[2].ratio-from", () -> 2);
        registry.add(prefix + "[2].multiplier", () -> 2);
    }

    @Autowired private TestCities cities;
    @Autowired private TestPrices prices;
    @Autowired private TestUsers users;
    @Autowired private GeographyApi geography;
    @Autowired private PricingApi pricing;
    @Autowired private LiveIndex index;
    @Autowired private CityOwnership ownership;
    @Autowired private Leases leases;
    @Autowired private ComputedSurge surge;
    @Autowired private ComputedSurgeRepository repository;
    @Autowired private ComputedSurgeProperties properties;
    @Autowired private MutableClock clock;
    @Autowired private JdbcClient jdbc;
    @Autowired private TransactionTemplate transactions;

    private TestCity city;
    private String zone;
    private String rider;

    @BeforeAll
    void prepareCity() {
        city = cities.createWithId(CITY, "MINI");
        prices.price(CITY, "MINI");
        zone = geography.locate(city.at(0.2, 0.2)).orElseThrow().zoneId();
        prices.surgeNow(CITY, zone, "1.50");
        rider = users.create(UserRole.RIDER).authorization();
    }

    @BeforeEach
    void startOwner() {
        for (String table : List.of("surge_history", "surge_multipliers", "surge_runs", "quotes")) {
            jdbc.sql("DELETE FROM pricing." + table + " WHERE city_id = :city").param("city", CITY).update();
        }
        index.mirrored(CITY).forEach((driver, state) -> index.mirror(CITY, driver,
                new MirrorState(Status.OFFLINE, state.version() + 1, null, null)));
        clock.advance(Duration.ofMinutes(2));
        assertThat(ownership.configuredCities()).containsExactly(CITY);
        ((SmartLifecycle) ownership).start();
        await().atMost(Duration.ofSeconds(5)).until(() -> !ownership.owned().isEmpty());
    }

    @AfterEach
    void stopOwner() {
        ((SmartLifecycle) ownership).stop();
    }

    @Test
    void theLivePollerChangesNewQuotesButNotExistingFaresAndFallsBackAfterExpiry() {
        JsonNode original = quoteThroughApi();
        assertThat(original.get("surge_multiplier").decimalValue()).isEqualByComparingTo("1.50");
        for (int count = 0; count < 9; count++) {
            quote(Ids.newId());
        }
        UUID driver = Ids.newId();
        index.mirror(CITY, driver, new MirrorState(Status.AVAILABLE, 1, "MINI", null));
        index.update(CITY, driver, "MINI", new LocationUpdate(1, city.at(0.2, 0.2), 5, null, null, clock.instant()));

        assertThat(surge.poll()).isTrue();
        JsonNode computed = quoteThroughApi();
        UUID computedId = UUID.fromString(computed.get("id").asString());
        assertThat(computed.get("surge_multiplier").decimalValue()).isEqualByComparingTo("1.20");
        assertThat(source(computedId)).isEqualTo("COMPUTED");
        assertThat(multiplier(UUID.fromString(original.get("id").asString()))).isEqualByComparingTo("1.50");
        long history = historyCount();
        assertThat(surge.poll()).isFalse();
        assertThat(historyCount()).isEqualTo(history);
        makeDue();
        assertThat(surge.poll()).isTrue();
        assertThat(quoteThroughApi().get("surge_multiplier").decimalValue()).isEqualByComparingTo("1.40");
        assertThat(multiplier(computedId)).isEqualByComparingTo("1.20");

        jdbc.sql("UPDATE pricing.surge_multipliers SET sampled_at = clock_timestamp() - interval '121 seconds' WHERE city_id = :city")
                .param("city", CITY).update();
        JsonNode fallback = quoteThroughApi();
        assertThat(fallback.get("surge_multiplier").decimalValue()).isEqualByComparingTo("1.50");
        assertThat(source(UUID.fromString(fallback.get("id").asString()))).isEqualTo("RULE");
        assertThat(pricing.quote(Ids.newId(), new QuoteRequest(city.at(0.05, 0.05), city.at(0.1, 0.1), "MINI"))
                .surgeMultiplier()).isEqualByComparingTo("1.00");
    }

    @Test
    void demandUsesConsumptionTimeCountsOnceAndIgnoresRolledBackOldFutureAndForeignBookings() {
        UUID owner = Ids.newId();
        quote(owner);
        UUID recent = consumedQuote(owner);
        UUID oldCreation = consumedQuote(owner);
        ageQuote(oldCreation, false);
        UUID oldBoth = consumedQuote(owner);
        ageQuote(oldBoth, true);
        UUID future = consumedQuote(owner);
        jdbc.sql("""
                UPDATE pricing.quotes SET created_at = clock_timestamp() + interval '1 minute',
                    used_at = clock_timestamp() + interval '1 minute', expires_at = clock_timestamp() + interval '6 minutes'
                WHERE id = :id
                """).param("id", future).update();
        UUID rolledBack = quote(owner);
        assertThatThrownBy(() -> transactions.executeWithoutResult(transaction -> {
            pricing.consume(rolledBack, owner, Ids.newId());
            throw new IllegalStateException("booking rollback");
        })).hasMessage("booking rollback");
        assertThatThrownBy(() -> transactions.executeWithoutResult(transaction -> pricing.consume(recent, owner, Ids.newId())))
                .isInstanceOf(com.ridehailing.platform.ApiException.class);
        TestCity other = cities.create("MINI");
        prices.price(other.id(), "MINI");
        pricing.quote(owner, new QuoteRequest(other.at(0.2, 0.2), other.at(0.3, 0.3), "MINI"));

        assertThat(repository.observe(CITY).demand()).containsOnlyKeys(zone).containsEntry(zone, new Demand(2, 3));
    }

    @Test
    void losingOwnershipDuringSamplingCannotPublishPreparedWork() {
        quote(Ids.newId());
        var captured = ownership.owned().getFirst();
        LiveIndex changing = mock(LiveIndex.class);
        when(changing.epoch(CITY)).thenReturn(clock.instant().minusSeconds(120));
        when(changing.available(CITY, properties.maxDrivers())).thenAnswer(invocation -> {
            leases.release("city-owner:" + CITY, captured.holder(), captured.token());
            return List.of();
        });
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ComputedSurge poller = new ComputedSurge(ownership, repository, properties, geography, changing, clock, metrics);

        assertThat(poller.poll()).isFalse();
        assertThat(repository.current(CITY, zone)).isEmpty();
        assertThat(repository.due(CITY)).isTrue();
        assertThat(historyCount()).isZero();
        assertThat(metrics.get("pricing.surge.cycles").tag("outcome", "not_owner").counter().count()).isEqualTo(1);
    }

    @Test
    void aFailedSupplyReadPreservesThePriorValueAndScheduleUntilNormalExpiry() {
        for (int count = 0; count < 10; count++) {
            quote(Ids.newId());
        }
        assertThat(surge.poll()).isTrue();
        makeDue();
        long history = historyCount();
        LiveIndex broken = mock(LiveIndex.class);
        when(broken.epoch(CITY)).thenReturn(clock.instant().minusSeconds(120));
        when(broken.available(CITY, properties.maxDrivers())).thenThrow(new IllegalStateException("supply unavailable"));
        ComputedSurge poller = new ComputedSurge(ownership, repository, properties, geography, broken, clock, new SimpleMeterRegistry());

        assertThat(poller.poll()).isFalse();
        assertThat(repository.current(CITY, zone)).contains(new BigDecimal("1.20"));
        assertThat(repository.due(CITY)).isTrue();
        assertThat(historyCount()).isEqualTo(history);
    }

    private UUID quote(UUID owner) {
        return pricing.quote(owner, new QuoteRequest(city.at(0.2, 0.2), city.at(0.25, 0.25), "MINI")).id();
    }

    private UUID consumedQuote(UUID owner) {
        UUID quoteId = quote(owner);
        transactions.executeWithoutResult(transaction -> pricing.consume(quoteId, owner, Ids.newId()));
        return quoteId;
    }

    private void ageQuote(UUID quoteId, boolean usedToo) {
        jdbc.sql("""
                UPDATE pricing.quotes SET created_at = clock_timestamp() - interval '6 minutes',
                    expires_at = clock_timestamp() - interval '1 minute',
                    used_at = CASE WHEN :usedToo THEN clock_timestamp() - interval '6 minutes' ELSE used_at END
                WHERE id = :id
                """).param("id", quoteId).param("usedToo", usedToo).update();
    }

    private JsonNode quoteThroughApi() {
        return assertAnswered("POST", "/v1/quotes", call("POST", rider, "/v1/quotes", """
                {"pickup":{"lat":%s,"lon":%s},"dropoff":{"lat":%s,"lon":%s},"category":"MINI"}
                """.formatted(city.at(0.2, 0.2).lat(), city.at(0.2, 0.2).lon(), city.at(0.25, 0.25).lat(), city.at(0.25, 0.25).lon())), 201);
    }

    private String source(UUID quoteId) {
        return jdbc.sql("SELECT surge_source FROM pricing.quotes WHERE id = :id").param("id", quoteId).query(String.class).single();
    }

    private BigDecimal multiplier(UUID quoteId) {
        return jdbc.sql("SELECT surge_multiplier FROM pricing.quotes WHERE id = :id").param("id", quoteId).query(BigDecimal.class).single();
    }

    private long historyCount() {
        return jdbc.sql("SELECT count(*) FROM pricing.surge_history WHERE city_id = :city").param("city", CITY).query(Long.class).single();
    }

    private void makeDue() {
        jdbc.sql("UPDATE pricing.surge_runs SET computed_at = clock_timestamp() - interval '61 seconds' WHERE city_id = :city")
                .param("city", CITY).update();
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableClock surgeClock() {
            return new MutableClock(Instant.now().minusSeconds(120));
        }
    }
}
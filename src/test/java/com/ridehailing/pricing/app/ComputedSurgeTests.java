package com.ridehailing.pricing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.geography.GeographyApi.ZoneCount;
import com.ridehailing.platform.CityOwnership.Claim;
import com.ridehailing.platform.Leases;
import com.ridehailing.pricing.app.ComputedSurgeCalculator.Step;
import com.ridehailing.pricing.db.ComputedSurgeRepository;
import com.ridehailing.pricing.db.ComputedSurgeRepository.Demand;
import com.ridehailing.pricing.db.ComputedSurgeRepository.Observation;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

class ComputedSurgeTests extends IntegrationTest {

    private static final List<Step> STEPS = List.of(new Step(BigDecimal.ZERO, BigDecimal.ONE),
            new Step(BigDecimal.ONE, new BigDecimal("1.50")), new Step(new BigDecimal("2"), new BigDecimal("2.00")));
    private static final String ZONE = "test-zone";

    @Autowired
    private ComputedSurgeRepository repository;

    @Autowired
    private Leases leases;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PricingRetention retention;

    private Claim claim;

    @BeforeEach
    void claimCity() {
        String city = TestCities.newId();
        long token = leases.acquire("city-owner:" + city, "surge-test", Duration.ofSeconds(10)).orElseThrow();
        claim = new Claim(city, "surge-test", token);
    }

    @Test
    void guardedUpdatesPersistCountsAndHistoryAndCannotRepeatedlyApplyDamping() {
        Observation observation = observation(10, 5);
        assertThat(commit(observation, Map.of(ZONE, new ZoneCount(1, 2)))).isTrue();
        assertThat(repository.current(claim.cityId(), ZONE)).contains(new BigDecimal("1.20"));
        assertThat(repository.current("another-city", ZONE)).isEmpty();
        assertThat(repository.current(claim.cityId(), "another-zone")).isEmpty();
        assertThat(repository.due(claim.cityId())).isFalse();
        assertThat(jdbc.sql("SELECT demand FROM pricing.surge_history WHERE city_id = :city")
                .param("city", claim.cityId()).query(BigDecimal.class).single()).isEqualByComparingTo("11.00");
        assertThat(jdbc.sql("SELECT supply FROM pricing.surge_history WHERE city_id = :city")
                .param("city", claim.cityId()).query(BigDecimal.class).single()).isEqualByComparingTo("2.00");
        assertThat(jdbc.sql("SELECT owner_token FROM pricing.surge_history WHERE city_id = :city")
            .param("city", claim.cityId()).query(Long.class).single()).isEqualTo(claim.token());
        assertThat(jdbc.sql("SELECT (steps->1->>'ratio_from')::numeric FROM pricing.surge_history WHERE city_id = :city")
            .param("city", claim.cityId()).query(BigDecimal.class).single()).isEqualByComparingTo("1.00");
        assertThat(commit(observation(10, 5), Map.of())).isFalse();
        assertThat(historyCount()).isEqualTo(1);
        makeDue();
        assertThat(repository.due(claim.cityId())).isTrue();
        assertThat(commit(observation(10, 5), Map.of())).isTrue();
        assertThat(repository.current(claim.cityId(), ZONE)).contains(new BigDecimal("1.40"));
        assertThat(historyCount()).isEqualTo(2);
        makeDue();
        assertThat(commit(repository.observe(claim.cityId()), Map.of())).isTrue();
        assertThat(repository.current(claim.cityId(), ZONE)).contains(new BigDecimal("1.20"));
    }

    @Test
    void expiredSamplesAndOldOwnersCannotWriteAndStaleValuesAreNotRead() {
        Observation observation = observation(10, 0);
        assertThat(commit(new Observation(observation.sampledAt().minusSeconds(11), observation.demand()), Map.of())).isFalse();
        assertThat(commit(new Observation(observation.sampledAt().plusSeconds(60), observation.demand()), Map.of())).isFalse();
        assertThat(historyCount()).isZero();
        assertThat(commit(observation, Map.of())).isTrue();
        jdbc.sql("UPDATE pricing.surge_multipliers SET sampled_at = clock_timestamp() - interval '2 minutes' WHERE city_id = :city")
                .param("city", claim.cityId()).update();
        assertThat(repository.current(claim.cityId(), ZONE)).isEmpty();
        makeDue();
        assertThat(commit(observation, Map.of())).isFalse();
        leases.release("city-owner:" + claim.cityId(), claim.holder(), claim.token());
        assertThat(commit(observation(10, 0), Map.of())).isFalse();
        assertThat(historyCount()).isEqualTo(1);
    }

    @Test
    void aRollbackRemovesTheScheduleValueAndHistoryTogether() {
        Observation observation = observation(10, 0);
        assertThatThrownBy(() -> leases.runIfHeld("city-owner:" + claim.cityId(), claim.holder(), claim.token(), () -> {
            assertThat(repository.commit(claim, observation, Map.of(), STEPS)).isTrue();
            throw new IllegalStateException("rollback");
        })).hasMessage("rollback");
        assertThat(historyCount()).isZero();
        assertThat(repository.current(claim.cityId(), ZONE)).isEmpty();
        assertThat(repository.due(claim.cityId())).isTrue();
        assertThatThrownBy(() -> repository.commit(claim, observation, Map.of(), STEPS))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("existing fenced transaction");
    }

    @Test
    void expiryAfterPublicationRollsBackTheWholeCycle() {
        assertThat(leases.runIfHeld("city-owner:" + claim.cityId(), claim.holder(), claim.token(), () -> {
            assertThat(repository.commit(claim, observation(10, 0), Map.of(), STEPS)).isTrue();
            jdbc.sql("UPDATE platform.leases SET expires_at = clock_timestamp() - interval '1 second' WHERE name = :name")
                    .param("name", "city-owner:" + claim.cityId()).update();
        })).isFalse();
        assertThat(historyCount()).isZero();
        assertThat(repository.current(claim.cityId(), ZONE)).isEmpty();
        assertThat(repository.due(claim.cityId())).isTrue();
    }

    @Test
    void historyExpiresAfterSevenDaysWithoutDeletingCurrentValues() {
        assertThat(commit(observation(10, 0), Map.of())).isTrue();
        jdbc.sql("UPDATE pricing.surge_history SET computed_at = clock_timestamp() - interval '7 days 1 second' WHERE city_id = :city")
                .param("city", claim.cityId()).update();
        retention.purge();
        assertThat(historyCount()).isZero();
        assertThat(repository.current(claim.cityId(), ZONE)).isPresent();
        makeDue();
        assertThat(commit(observation(10, 0), Map.of())).isTrue();
        retention.purge();
        assertThat(historyCount()).isEqualTo(1);
    }

    @Test
    void tooManyZonesRollBackTheScheduleAndNeverPublishPartialPrices() {
        Map<String, Demand> demand = new HashMap<>();
        for (int number = 0; number <= ComputedSurgeRepository.MAX_ZONES; number++) {
            demand.put("zone-" + number, new Demand(1, 0));
        }
        Observation oversized = new Observation(repository.observe(claim.cityId()).sampledAt(), demand);
        assertThatThrownBy(() -> commit(oversized, Map.of())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zone limit");
        assertThat(repository.due(claim.cityId())).isTrue();
        assertThat(historyCount()).isZero();
    }

    @Test
    void databaseConstraintsRejectNonfiniteAndOutOfRangePricesAndMeasurements() {
        assertThat(commit(observation(10, 0), Map.of())).isTrue();
        for (String invalid : List.of("NaN", "0.99", "2.01")) {
            assertThatThrownBy(() -> jdbc.sql("UPDATE pricing.surge_multipliers SET multiplier = CAST(:value AS numeric) WHERE city_id = :city")
                    .param("value", invalid).param("city", claim.cityId()).update()).isInstanceOf(DataIntegrityViolationException.class);
        }
        for (String invalid : List.of("NaN", "-0.01")) {
            assertThatThrownBy(() -> jdbc.sql("UPDATE pricing.surge_history SET demand = CAST(:value AS numeric) WHERE city_id = :city")
                    .param("value", invalid).param("city", claim.cityId()).update()).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(repository.current(claim.cityId(), ZONE)).contains(new BigDecimal("1.20"));
    }

    private boolean commit(Observation observation, Map<String, ZoneCount> supply) {
        AtomicBoolean updated = new AtomicBoolean();
        return leases.runIfHeld("city-owner:" + claim.cityId(), claim.holder(), claim.token(),
                () -> updated.set(repository.commit(claim, observation, supply, STEPS))) && updated.get();
    }

    private Observation observation(long rides, long quotes) {
        return new Observation(repository.observe(claim.cityId()).sampledAt(), Map.of(ZONE, new Demand(rides, quotes)));
    }

    private void makeDue() {
        jdbc.sql("UPDATE pricing.surge_runs SET computed_at = clock_timestamp() - interval '61 seconds' WHERE city_id = :city")
                .param("city", claim.cityId()).update();
    }

    private long historyCount() {
        return jdbc.sql("SELECT count(*) FROM pricing.surge_history WHERE city_id = :city")
                .param("city", claim.cityId()).query(Long.class).single();
    }
}
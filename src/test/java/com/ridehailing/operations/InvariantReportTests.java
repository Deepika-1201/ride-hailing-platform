package com.ridehailing.operations;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.shared.UserRole;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestUsers;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** LLD §17.3: the invariant checks over every city, as the simulator calls them after a run (ADR-021). */
class InvariantReportTests extends IntegrationTest {

    private static final String INVARIANTS = "/v1/ops/invariants";

    @Autowired
    TestCities cities;

    @Autowired
    TestPrices prices;

    @Autowired
    TestRides rides;

    @Autowired
    TestUsers users;

    @Autowired
    JdbcClient jdbc;

    @Test
    void operationsSeeEveryCheckAndAViolationInAnyCityWithItsIds() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        TestDriver driver = rides.onlineAt(city, "MINI", city.at(0.1, 0.1));
        jdbc.sql("UPDATE driver.drivers SET suspended = true, suspension_reason = 'Broken on purpose' WHERE id = :id")
                .param("id", driver.id()).update();
        try {
            Instant before = Instant.now();

            JsonNode report = assertAnswered("GET", INVARIANTS,
                    getAs(users.create(UserRole.OPS).authorization(), INVARIANTS), 200);

            assertThat(report.get("checks").valueStream().map(JsonNode::asString))
                    .containsExactly("I1", "I2", "I3", "I4", "I5", "I6", "I7", "I8");
            assertThat(Instant.parse(report.get("checked_at").asString())).isBetween(before.minusSeconds(1),
                    before.plus(Duration.ofMinutes(1)));
            assertThat(report.get("violations").valueStream()
                    .filter(v -> v.get("detail").asString().contains(driver.id().toString())))
                    .singleElement().satisfies(violation -> {
                        assertThat(violation.get("invariant").asString()).isEqualTo("I8");
                        assertThat(violation.get("detail").asString()).isEqualTo("driver " + driver.id()
                                + " is suspended but AVAILABLE");
                        assertThat(violation.get("ids").valueStream().map(JsonNode::asString))
                                .containsExactly(driver.id().toString());
                    });
        } finally {
            jdbc.sql("UPDATE driver.drivers SET suspended = false, suspension_reason = NULL WHERE id = :id")
                    .param("id", driver.id()).update();
        }
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void onlyOperationsAndAdminsRunTheChecks() {
        assertAnswered("GET", INVARIANTS, getAs(users.create(UserRole.ADMIN).authorization(), INVARIANTS), 200);
        for (UserRole role : List.of(UserRole.RIDER, UserRole.DRIVER)) {
            assertProblem("GET", INVARIANTS, getAs(users.create(role).authorization(), INVARIANTS), 403, "FORBIDDEN");
        }
        assertProblem("GET", INVARIANTS, call("GET", null, INVARIANTS, null), 401, "UNAUTHENTICATED");
    }
}

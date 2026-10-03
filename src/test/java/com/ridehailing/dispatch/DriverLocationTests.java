package com.ridehailing.dispatch;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.Candidate;
import com.ridehailing.location.LiveIndex.LocationUpdate;
import com.ridehailing.location.LocationIngestion;
import com.ridehailing.location.LocationIngestion.BatchResult;
import com.ridehailing.platform.LogContext;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers;
import com.ridehailing.support.TestDrivers.TestDriver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Locations over REST (LLD §9.6): sequence order, stale and duplicate updates, bounds, offline drivers, the limit. */
class DriverLocationTests extends IntegrationTest {

    private static final String LOCATION = "/v1/drivers/me/location";

    @Autowired
    private TestCities cities;

    @Autowired
    private TestDrivers drivers;

    @Autowired
    private DispatchApi dispatch;

    @Autowired
    private LocationIngestion ingestion;

    @Autowired
    private LiveIndex index;

    @Autowired
    private MeterRegistry meters;

    private TestCity city;
    private TestDriver driver;

    @BeforeEach
    void setUp() {
        city = cities.create("MINI", "SEDAN");
        driver = drivers.create(city, "MINI");
    }

    @Test
    void anOnlineDriversUpdatesAreAppliedInSequenceOrder() {
        goOnline(driver);
        GeoPoint last = city.at(0.2, 0.2);

        JsonNode result = assertAnswered("POST", LOCATION, send(driver,
                update(3, last), update(1, city.at(0.1, 0.1)), update(2, city.at(0.15, 0.15))), 200);

        assertThat(result.get("applied").asInt()).isEqualTo(3);
        assertThat(result.get("stale").asInt()).isZero();
        assertThat(result.get("ignored").asInt()).isZero();
        assertThat(result.get("last_applied_seq").asLong()).isEqualTo(3);
        assertThat(index.position(city.id(), driver.id())).hasValueSatisfying(position -> {
            assertThat(position.seq()).isEqualTo(3);
            assertThat(position.position()).isEqualTo(last);
        });
        assertThat(index.nearby(city.id(), "MINI", last, 1_000, 5)).extracting(Candidate::driverId)
                .containsExactly(driver.id());
    }

    @Test
    void duplicateOlderAndOutOfBoundsUpdatesAreCountedButNotApplied() {
        goOnline(driver);
        GeoPoint applied = city.at(0.2, 0.2);
        assertAnswered("POST", LOCATION, send(driver, update(5, applied)), 200);

        BatchResult result = ingestion.accept(city.id(), "MINI", driver.id(), List.of(
                updateOf(5, city.at(0.3, 0.3)), updateOf(4, city.at(0.3, 0.3)),
                updateOf(6, city.at(TestCities.SIZE + 0.01, 0.2)), updateOf(7, city.at(0.2, -0.01))));

        assertThat(result).isEqualTo(new BatchResult(0, 2, 2, null));
        assertThat(index.position(city.id(), driver.id())).hasValueSatisfying(position -> {
            assertThat(position.seq()).isEqualTo(5);
            assertThat(position.position()).isEqualTo(applied);
        });
    }

    @Test
    void anOfflineDriversUpdatesAreIgnoredWithoutTouchingTheIndex() {
        double counted = countedUpdates();

        JsonNode result = assertAnswered("POST", LOCATION, send(driver, update(1, city.at(0.1, 0.1)),
                update(2, city.at(0.1, 0.1))), 200);

        assertThat(result.get("ignored").asInt()).isEqualTo(2);
        assertThat(result.get("applied").asInt()).isZero();
        assertThat(result.has("last_applied_seq")).isFalse();
        assertThat(index.mirrored(city.id())).doesNotContainKey(driver.id());
        assertThat(countedUpdates()).as("ingestion never saw them").isEqualTo(counted);
    }

    @Test
    void anUpdateSentForTheWrongCategoryIsAppliedInTheMirrorsCategory() {
        goOnline(driver);

        BatchResult result = ingestion.accept(city.id(), "SEDAN", driver.id(), List.of(updateOf(1, city.at(0.1,
                0.1))));

        assertThat(result).isEqualTo(new BatchResult(1, 0, 0, 1L));
        assertThat(index.nearby(city.id(), "MINI", city.at(0.1, 0.1), 100, 5)).extracting(Candidate::driverId)
                .containsExactly(driver.id());
    }

    @Test
    void anUnknownCityIgnoresEveryUpdate() {
        assertThat(ingestion.accept(TestCities.newId(), "MINI", driver.id(), List.of(updateOf(1, city.at(0.1, 0.1)))))
                .isEqualTo(BatchResult.allIgnored(1));
    }

    @Test
    void moreThanOneRequestASecondIsRateLimited() {
        goOnline(driver);
        long started = System.nanoTime();
        List<Integer> statuses = new ArrayList<>();
        HttpResponse<String> limited = null;
        for (int seq = 1; seq <= 5 && limited == null; seq++) {
            HttpResponse<String> response = send(driver, update(seq, city.at(0.1, 0.1)));
            statuses.add(response.statusCode());
            if (response.statusCode() == 429) {
                limited = response;
            }
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        assertThat(limited).as("statuses %s", statuses).isNotNull();
        assertProblem("POST", LOCATION, limited, 429, "RATE_LIMITED");
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        assertThat(statuses.size() - 1).as("allowed in %.2f s", seconds).isBetween(1, 1 + (int) Math.ceil(seconds));
    }

    @Test
    void malformedUpdatesAreRejected() {
        String valid = update(1, city.at(0.1, 0.1));
        for (String body : List.of(
                "{\"updates\": []}",
                "{\"updates\": [" + valid.replace("\"device_time\"", "\"other_time\"") + "]}",
                "{\"updates\": [" + valid.replace("\"heading_deg\": 90", "\"heading_deg\": 360") + "]}",
                "{\"updates\": [" + valid.replace("\"accuracy_m\": 5", "\"accuracy_m\": -1") + "]}",
                "{\"updates\": [" + IntStream.rangeClosed(1, 101).mapToObj(seq -> update(seq, city.at(0.1, 0.1)))
                        .collect(Collectors.joining(", ")) + "]}")) {
            assertProblem("POST", LOCATION, call("POST", driver.authorization(), LOCATION, body), 400,
                    "VALIDATION_FAILED");
        }
    }

    private void goOnline(TestDriver who) {
        LogContext.run(Map.of(LogContext.ROLE, "api"), () -> dispatch.goOnline(who.id(), who.vehicleId()));
    }

    private double countedUpdates() {
        return meters.find("location.updates").counters().stream().mapToDouble(Counter::count).sum();
    }

    private HttpResponse<String> send(TestDriver who, String... updates) {
        return call("POST", who.authorization(), LOCATION, "{\"updates\": [" + String.join(", ", Arrays.asList(updates))
                + "]}");
    }

    private static String update(long seq, GeoPoint at) {
        return """
                {"seq": %d, "lat": %s, "lon": %s, "accuracy_m": 5, "heading_deg": 90, "speed_mps": 7.5,
                 "device_time": "%s"}""".formatted(seq, at.lat(), at.lon(), Instant.now());
    }

    private static LocationUpdate updateOf(long seq, GeoPoint at) {
        return new LocationUpdate(seq, at, 5, null, null, Instant.now());
    }
}

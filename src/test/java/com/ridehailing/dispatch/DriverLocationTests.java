package com.ridehailing.dispatch;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.Candidate;
import com.ridehailing.location.LiveIndex.LocationUpdate;
import com.ridehailing.location.LocationIngestion;
import com.ridehailing.location.LocationIngestion.BatchResult;
import com.ridehailing.location.trips.TripPointBuffer;
import com.ridehailing.platform.EventStream;
import com.ridehailing.platform.LogContext;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Locations over REST (LLD §9.6): sequence order, stale and duplicate updates, bounds, offline drivers, the limit. */
@Import(DriverLocationTests.StreamConfiguration.class)
class DriverLocationTests extends IntegrationTest {

    private static final String LOCATION = "/v1/drivers/me/location";
        private static final JsonNode STREAM_DOCUMENT = JsonMapper.builder().build()
            .readTree(Path.of("docs/schemas/location/update.v1.json").toFile());
        private static final Schema STREAM_SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()))
            .getSchema(STREAM_DOCUMENT);

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

    @Autowired
    private CapturingStream stream;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestRides rides;

    @Autowired
    private TripPointBuffer tripPoints;

    private TestCity city;
    private TestDriver driver;

    @BeforeEach
    void setUp() {
        stream.sent.clear();
        stream.failing = false;
        city = cities.create("MINI", "SEDAN");
        driver = drivers.create(city, "MINI");
    }

    @Test
    void anOnlineDriversUpdatesAreAppliedInSequenceOrder() {
        goOnline(driver);
        GeoPoint last = city.at(0.2, 0.2);

        // A few metres apart, so no step is an implausible jump (§9.5).
        JsonNode result = assertAnswered("POST", LOCATION, send(driver,
                update(3, last), update(1, city.at(0.1999, 0.1999)), update(2, city.at(0.19995, 0.19995))), 200);

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
        assertThat(stream.sent).extracting(record -> record.value().get("seq").asLong()).containsExactly(1L, 2L, 3L);
        assertThat(stream.sent).allSatisfy(record -> {
            assertThat(record.topic()).isEqualTo("location.updates");
            assertThat(record.key()).isEqualTo(driver.id());
            assertThat(record.value().get("driver_id").asString()).isEqualTo(driver.id().toString());
            assertThat(record.value().get("city_id").asString()).isEqualTo(city.id());
            assertThat(record.value().get("category").asString()).isEqualTo("MINI");
            assertThat(record.value().get("schema_version").asInt()).isEqualTo(1);
            assertThat(record.value().get("status").asString()).isEqualTo("AVAILABLE");
            assertThat(record.value().get("flags").asInt()).isZero();
            assertThat(record.value().get("replay").asBoolean()).isFalse();
            assertThat(record.value().has("received_at")).isTrue();
        });
    }

    @Test
    void duplicateOlderAndOutOfBoundsUpdatesAreCountedButNotApplied() {
        goOnline(driver);
        GeoPoint applied = city.at(0.2, 0.2);
        assertAnswered("POST", LOCATION, send(driver, update(5, applied)), 200);
        stream.sent.clear();

        BatchResult result = ingestion.accept(city.id(), "MINI", driver.id(), List.of(
                updateOf(5, city.at(0.3, 0.3)), updateOf(4, city.at(0.3, 0.3)),
                updateOf(6, city.at(TestCities.SIZE + 0.01, 0.2)), updateOf(7, city.at(0.2, -0.01))));

        assertThat(result).isEqualTo(new BatchResult(0, 2, 2, null));
        assertThat(stream.sent).isEmpty();
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
        assertThat(stream.sent).isEmpty();
    }

    @Test
    void anUpdateSentForTheWrongCategoryIsAppliedInTheMirrorsCategory() {
        goOnline(driver);

        BatchResult result = ingestion.accept(city.id(), "SEDAN", driver.id(), List.of(updateOf(1, city.at(0.1,
                0.1))));

        assertThat(result).isEqualTo(new BatchResult(1, 0, 0, 1L));
        assertThat(index.nearby(city.id(), "MINI", city.at(0.1, 0.1), 100, 5)).extracting(Candidate::driverId)
                .containsExactly(driver.id());
        assertThat(stream.sent).singleElement().satisfies(record -> assertThat(record.value().get("category").asString()).isEqualTo("MINI"));
    }

    @Test
    void anUnknownCityIgnoresEveryUpdate() {
        assertThat(ingestion.accept(TestCities.newId(), "MINI", driver.id(), List.of(updateOf(1, city.at(0.1, 0.1)))))
                .isEqualTo(BatchResult.allIgnored(1));
        assertThat(stream.sent).isEmpty();
        }

        @Test
        void theStreamPreservesQualityFlagsAndAppliedReplayMetadata() {
        goOnline(driver);
        GeoPoint position = city.at(0.1, 0.1);
        LocationUpdate poor = new LocationUpdate(1, position, 250, 90.0, 7.5, Instant.now());

        assertThat(ingestion.live(city.id(), "MINI", driver.id(), poor)).hasValueSatisfying(result ->
            assertThat(result.flags()).isEqualTo(LiveIndex.UpdateResult.POOR_ACCURACY));
        ingestion.accept(city.id(), "MINI", driver.id(), List.of(new LocationIngestion.Incoming(updateOf(2, position), true)),
            deviceTime -> Optional.empty());

        assertThat(stream.sent).hasSize(2);
        JsonNode first = stream.sent.getFirst().value();
        assertThat(first.get("flags").asInt()).isEqualTo(LiveIndex.UpdateResult.POOR_ACCURACY);
        assertThat(first.get("accuracy_m").asDouble()).isEqualTo(250);
        assertThat(first.get("lat").asDouble()).isEqualTo(position.lat());
        assertThat(first.get("lon").asDouble()).isEqualTo(position.lon());
        assertThat(first.get("heading_deg").asDouble()).isEqualTo(90);
        assertThat(first.get("speed_mps").asDouble()).isEqualTo(7.5);
        assertThat(Instant.parse(first.get("device_time").asString())).isEqualTo(poor.deviceTime());
        assertThat(stream.sent.getLast().value().get("replay").asBoolean()).isTrue();
        }

        @Test
        void publicationFailureNeverRejectsAnAppliedLiveUpdate() {
        goOnline(driver);
        stream.failing = true;
        double before = meters.counter("location.stream.failures").count();

        assertThat(ingestion.live(city.id(), "MINI", driver.id(), updateOf(1, city.at(0.1, 0.1))))
            .hasValueSatisfying(result -> assertThat(result.outcome()).isEqualTo(LiveIndex.UpdateResult.Outcome.APPLIED));

        assertThat(index.position(city.id(), driver.id())).hasValueSatisfying(position -> assertThat(position.seq()).isEqualTo(1));
        assertThat(meters.counter("location.stream.failures").count()).isEqualTo(before + 1);
    }

    @Test
    void activeRideMetadataIsPublishedAndStreamFailureDoesNotLoseTripPoints() {
        prices.price(city.id(), "MINI");
        var ride = rides.assigned(city, city.at(0.1, 0.1), city.at(0.1, 0.101));
        stream.sent.clear();
        long firstSequence = index.position(city.id(), ride.driver().id()).orElseThrow().seq() + 1;
        ingestion.live(city.id(), "MINI", ride.driver().id(), updateOf(firstSequence, city.at(0.1, 0.1011)));
        assertThat(stream.sent).singleElement().satisfies(record -> {
            assertThat(record.key()).isEqualTo(ride.driver().id());
            assertThat(record.value().get("ride_id").asString()).isEqualTo(ride.id().toString());
            assertThat(record.value().get("status").asString()).isEqualTo("ASSIGNED");
        });
        stream.failing = true;

        ingestion.live(city.id(), "MINI", ride.driver().id(), updateOf(firstSequence + 1, city.at(0.1, 0.1012)));
        tripPoints.flush();

        JsonNode route = assertAnswered("GET", "/v1/rides/{ride_id}/route",
                getAs(ride.rider().authorization(), "/v1/rides/" + ride.id() + "/route"), 200);
        assertThat(route.get("points").valueStream().map(point -> point.get("seq").asLong()).toList())
                .contains(firstSequence, firstSequence + 1);
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

    @Test
    void thePublishedSchemaExamplesConform() {
        STREAM_DOCUMENT.get("examples").valueStream().forEach(example -> assertThat(STREAM_SCHEMA.validate(example)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-seq", "negative-seq", "invalid-driver", "invalid-time", "unknown-field", "unknown-version", "unknown-flags"})
    void theStreamContractRejectsInvalidWireValues(String defect) {
        goOnline(driver);
        ingestion.live(city.id(), "MINI", driver.id(), updateOf(1, city.at(0.1, 0.1)));
        ObjectNode value = (ObjectNode) stream.sent.getFirst().value();
        switch (defect) {
            case "missing-seq" -> value.remove("seq");
            case "negative-seq" -> value.put("seq", -1);
            case "invalid-driver" -> value.put("driver_id", "not-a-uuid");
            case "invalid-time" -> value.put("received_at", "tomorrow");
            case "unknown-field" -> value.put("unexpected", true);
            case "unknown-version" -> value.put("schema_version", 2);
            case "unknown-flags" -> value.put("flags", 4);
            default -> throw new IllegalArgumentException(defect);
        }
        assertThat(STREAM_SCHEMA.validate(value)).isNotEmpty();
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

    record StreamRecord(String topic, UUID key, JsonNode value) {
    }

    static class CapturingStream implements EventStream {

        private final JsonMapper json;
        final List<StreamRecord> sent = new CopyOnWriteArrayList<>();
        volatile boolean failing;

        CapturingStream(JsonMapper json) {
            this.json = json;
        }

        @Override
        public boolean offer(String topic, UUID key, Object value) {
            if (failing) {
                throw new IllegalStateException("stream unavailable");
            }
            JsonNode encoded = json.valueToTree(value);
            assertThat(STREAM_SCHEMA.validate(encoded)).isEmpty();
            sent.add(new StreamRecord(topic, key, encoded));
            return true;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StreamConfiguration {

        @Bean
        CapturingStream capturingStream(JsonMapper json) {
            return new CapturingStream(json);
        }
    }
}

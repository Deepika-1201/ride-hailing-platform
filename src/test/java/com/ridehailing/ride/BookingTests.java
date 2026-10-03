package com.ridehailing.ride;

import static com.ridehailing.support.EventContract.outboxEvents;
import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.Idempotency;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.EventContract;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** T1 and T4 through the API (LLD §7.2, §7.4): checks inside the transaction, the search task and its timer. */
class BookingTests extends IntegrationTest {

    private static final String RIDES = "/v1/rides";
    private static final String RIDE = "/v1/rides/{ride_id}";
    private static final String CANCEL = "/v1/rides/{ride_id}/cancel";

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestRides rides;

    @Autowired
    private TestUsers users;

    @Autowired
    private JdbcClient jdbc;

    private TestCity city;
    private TestUser rider;
    private GeoPoint pickup;
    private GeoPoint dropoff;

    @BeforeEach
    void setUp() {
        city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        rider = rides.rider("Aditi");
        pickup = city.at(0.1, 0.1);
        dropoff = city.at(0.2, 0.15);
    }

    @Test
    void aBookingStartsTheSearchWithItsTaskAndTimer() {
        UUID quoteId = rides.quote(rider.id(), pickup, dropoff, "MINI");

        JsonNode ride = assertAnswered("POST", RIDES, book(rider, quoteId, key()), 201);

        UUID rideId = UUID.fromString(ride.get("id").asString());
        assertThat(ride.get("status").asString()).isEqualTo("SEARCHING");
        assertThat(ride.get("version").asInt()).isZero();
        assertThat(ride.get("payment_method").get("type").asString()).isEqualTo("CASH");
        assertThat(ride.has("pin") || ride.has("rider") || ride.has("driver")).isFalse();
        Instant requestedAt = Instant.parse(ride.get("requested_at").asString());
        assertThat(jdbc.sql("SELECT used_by_ride_id FROM pricing.quotes WHERE id = :id").param("id", quoteId)
                .query(UUID.class).single()).isEqualTo(rideId);
        assertThat(jdbc.sql("""
                        SELECT radius_m || ' ' || priority || ' ' || attempt || ' ' || (due_at <= now())
                        FROM dispatch.search_tasks WHERE ride_id = :rideId
                        """).param("rideId", rideId).query(String.class).single()).isEqualTo("2000 0 0 true");
        assertThat(jdbc.sql("""
                        SELECT due_at, payload ->> 'generation' AS generation FROM platform.timers
                        WHERE kind = 'SEARCH_TIMEOUT' AND aggregate_id = :rideId
                        """).param("rideId", rideId).query((row, n) -> Map.entry(
                        row.getObject("due_at", OffsetDateTime.class).toInstant(), row.getString("generation")))
                .single()).isEqualTo(Map.entry(requestedAt.plus(Duration.ofSeconds(180)), "1"));
        assertThat(outboxEvents(jdbc, "RideRequested", rideId)).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("quote_id").asString()).isEqualTo(quoteId.toString());
        });
        assertThat(jdbc.sql("""
                        SELECT coalesce(from_status, '-') || ' ' || to_status || ' ' || command || ' ' || actor_type
                        FROM ride.transitions WHERE ride_id = :rideId
                        """).param("rideId", rideId).query(String.class).list()).containsExactly(
                "- SEARCHING BOOK RIDER");
    }

    @Test
    void aRepeatedKeyReplaysTheBooking() {
        UUID quoteId = rides.quote(rider.id(), pickup, dropoff, "MINI");
        String key = key();

        HttpResponse<String> first = book(rider, quoteId, key);
        HttpResponse<String> replay = book(rider, quoteId, key);

        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue(Idempotency.REPLAYED_HEADER)).contains("true");
        assertThat(json(replay)).isEqualTo(json(first));
        assertThat(ridesOf(rider)).isEqualTo(1);
    }

    @Test
    void aQuoteMustBeTheRidersOwnUnusedAndUnexpired() {
        UUID quoteId = rides.quote(rider.id(), pickup, dropoff, "MINI");
        TestUser other = rides.rider("Rahul");
        assertProblem("POST", RIDES, book(other, quoteId, key()), 404, "NOT_FOUND");

        jdbc.sql("""
                        UPDATE pricing.quotes
                        SET created_at = now() - interval '10 minutes', expires_at = now() - interval '1 second'
                        WHERE id = :id
                        """).param("id", quoteId).update();
        assertProblem("POST", RIDES, book(rider, quoteId, key()), 409, "QUOTE_EXPIRED");

        UUID fresh = rides.quote(rider.id(), pickup, dropoff, "MINI");
        assertAnswered("POST", RIDES, book(rider, fresh, key()), 201);
        assertProblem("POST", RIDES, book(rider, fresh, key()), 409, "QUOTE_ALREADY_USED");
    }

    @Test
    void aRiderWithAnActiveRideCantBookAnotherAndKeepsTheQuote() {
        assertAnswered("POST", RIDES, book(rider, rides.quote(rider.id(), pickup, dropoff, "MINI"), key()), 201);
        UUID second = rides.quote(rider.id(), pickup, dropoff, "MINI");

        assertProblem("POST", RIDES, book(rider, second, key()), 409, "ACTIVE_RIDE_EXISTS");

        assertThat(jdbc.sql("SELECT used_by_ride_id IS NULL FROM pricing.quotes WHERE id = :id").param("id", second)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void thePaymentMethodMustBeTheRidersAndActive() {
        UUID quoteId = rides.quote(rider.id(), pickup, dropoff, "MINI");

        assertProblem("POST", RIDES, command(rider, RIDES, """
                {"quote_id": "%s", "payment_method_id": "%s"}""".formatted(quoteId, Ids.newId())), 422,
                "PAYMENT_METHOD_INVALID");
    }

    @Test
    void theRiderSeesTheirRideAndNobodyElseDoes() {
        JsonNode booked = json(book(rider, rides.quote(rider.id(), pickup, dropoff, "MINI"), key()));
        String path = RIDES + "/" + booked.get("id").asString();

        JsonNode ride = assertAnswered("GET", RIDE, call("GET", rider.authorization(), path, null), 200);
        assertThat(ride).isEqualTo(booked);
        assertProblem("GET", RIDE, call("GET", rides.rider("Rahul").authorization(), path, null), 404, "NOT_FOUND");
        assertProblem("GET", RIDE, call("GET", users.create(UserRole.DRIVER).authorization(), path, null), 404,
                "NOT_FOUND");
    }

    @Test
    void theRiderCancelsWhileSearchingFreeOfCharge() {
        JsonNode booked = json(book(rider, rides.quote(rider.id(), pickup, dropoff, "MINI"), key()));
        UUID rideId = UUID.fromString(booked.get("id").asString());
        String path = RIDES + "/" + rideId + "/cancel";

        JsonNode ride = assertAnswered("POST", CANCEL, command(rider, path, """
                {"reason": "Plans changed"}"""), 200);

        assertThat(ride.get("status").asString()).isEqualTo("CANCELLED_BY_RIDER");
        assertThat(ride.get("cancellation").get("cancelled_by").asString()).isEqualTo("RIDER");
        assertThat(ride.get("cancellation").has("fee")).isFalse();
        assertThat(ride.has("ended_at")).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM dispatch.search_tasks WHERE ride_id = :id").param("id", rideId)
                .query(Long.class).single()).isZero();
        assertThat(rides.timers().exists("SEARCH_TIMEOUT", rideId)).isFalse();
        assertThat(outboxEvents(jdbc, "RideCancelled", rideId)).singleElement().satisfies(EventContract::assertConforms);
        JsonNode again = assertAnswered("POST", CANCEL, command(rider, path, "{}"), 200);
        assertThat(again.get("version").asInt()).isEqualTo(ride.get("version").asInt());
        assertThat(outboxEvents(jdbc, "RideCancelled", rideId)).hasSize(1);
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void someoneElsesRideCantBeCancelled() {
        JsonNode booked = json(book(rider, rides.quote(rider.id(), pickup, dropoff, "MINI"), key()));
        String path = RIDES + "/" + booked.get("id").asString() + "/cancel";

        assertProblem("POST", CANCEL, command(rides.rider("Rahul"), path, "{}"), 404, "NOT_FOUND");
    }

    private HttpResponse<String> command(TestUser who, String path, String body) {
        return postJson(path, Map.of("Authorization", who.authorization(), Idempotency.HEADER, key()), body);
    }

    private HttpResponse<String> book(TestUser who, UUID quoteId, String key) {
        return postJson(RIDES, Map.of("Authorization", who.authorization(), Idempotency.HEADER, key),
                "{\"quote_id\": \"" + quoteId + "\"}");
    }

    private long ridesOf(TestUser who) {
        return jdbc.sql("SELECT count(*) FROM ride.rides WHERE rider_id = :id").param("id", who.id())
                .query(Long.class).single();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}

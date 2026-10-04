package com.ridehailing.support;

import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.LocationUpdate;
import com.ridehailing.platform.InvariantCheck;
import com.ridehailing.platform.LogContext;
import com.ridehailing.platform.Poller;
import com.ridehailing.platform.timers.TimerFiring;
import com.ridehailing.pricing.PricingApi;
import com.ridehailing.pricing.PricingApi.QuoteRequest;
import com.ridehailing.ride.RideView;
import com.ridehailing.ride.app.Booking;
import com.ridehailing.rider.app.RiderProfiles;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestUsers.TestUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Riders, rides, online drivers, search attempts and timers for dispatch tests, driven straight through the services
 * with the background loops stopped. Cities need {@code TestPrices.price} for their categories first.
 */
@TestComponent
public class TestRides {

    public static final String SEARCH_TASK_POLLER = "search-task-poller";

    private final TestUsers users;
    private final TestDrivers drivers;
    private final RiderProfiles profiles;
    private final PricingApi pricing;
    private final Booking booking;
    private final DispatchApi dispatch;
    private final LiveIndex index;
    private final ObjectProvider<Poller> pollers;
    private final ObjectProvider<InvariantCheck> checks;
    private final TimerFiring timers;
    private final JdbcClient jdbc;

    TestRides(TestUsers users, TestDrivers drivers, RiderProfiles profiles, PricingApi pricing, Booking booking,
            DispatchApi dispatch, LiveIndex index, ObjectProvider<Poller> pollers, ObjectProvider<InvariantCheck> checks,
            TimerFiring timers, JdbcClient jdbc) {
        this.users = users;
        this.drivers = drivers;
        this.profiles = profiles;
        this.pricing = pricing;
        this.booking = booking;
        this.dispatch = dispatch;
        this.index = index;
        this.pollers = pollers;
        this.checks = checks;
        this.timers = timers;
        this.jdbc = jdbc;
    }

    /** A rider with a first name, as seeded riders have. */
    public TestUser rider(String firstName) {
        TestUser rider = users.create(UserRole.RIDER);
        profiles.update(rider.id(), firstName, null, null);
        return rider;
    }

    public UUID quote(UUID riderId, GeoPoint pickup, GeoPoint dropoff, String category) {
        return pricing.quote(riderId, new QuoteRequest(pickup, dropoff, category)).id();
    }

    /** A ride in {@code SEARCHING} from the pickup to a drop-off 0.1° away, with its task due now. */
    public RideView book(UUID riderId, TestCity city, GeoPoint pickup, String category) {
        GeoPoint dropoff = new GeoPoint(Math.min(pickup.lat() + 0.1, city.lat() + TestCities.SIZE - 0.01),
                pickup.lon());
        UUID quoteId = quote(riderId, pickup, dropoff, category);
        return asApi(() -> booking.book(riderId, quoteId, null));
    }

    /** A verified driver online in the category, heard from just now at the position. */
    public TestDriver onlineAt(TestCity city, String category, GeoPoint position) {
        TestDriver driver = drivers.create(city, category);
        asApi(() -> dispatch.goOnline(driver.id(), driver.vehicleId()));
        report(driver, 1, position);
        return driver;
    }

    public void report(TestDriver driver, long seq, GeoPoint position) {
        index.update(driver.city().id(), driver.id(), driver.category(),
                new LocationUpdate(seq, position, 5, null, null, Instant.now()));
    }

    /** Runs search attempts until no task is due; answers how many ran. */
    public int search() {
        Poller poller = searchTaskPoller();
        int attempts = 0;
        while (asDispatch(poller::poll)) {
            attempts++;
        }
        return attempts;
    }

    /**
     * A ride booked by a new rider near an online driver, offered to them and accepted: {@code DRIVER_ASSIGNED},
     * with the PIN only the rider sees.
     */
    public AssignedRide assigned(TestCity city, GeoPoint pickup, GeoPoint driverAt) {
        TestDriver driver = onlineAt(city, "MINI", driverAt);
        TestUser rider = rider("Rider");
        RideView booked = book(rider.id(), city, pickup, "MINI");
        onlyDueIn(city.id());
        search();
        UUID offer = pendingOffer(booked.id());
        if (offer == null) {
            throw new IllegalStateException("No offer for ride " + booked.id());
        }
        asApi(() -> dispatch.accept(offer, driver.id()));
        String pin = jdbc.sql("SELECT pin FROM ride.rides WHERE id = :id").param("id", booked.id())
                .query(String.class).single();
        return new AssignedRide(booked.id(), rider, driver, offer, pin);
    }

    public record AssignedRide(UUID id, TestUser rider, TestDriver driver, UUID offerId, String pin) {
    }

    public Poller searchTaskPoller() {
        return pollers.stream().filter(poller -> poller.name().equals(SEARCH_TASK_POLLER)).findFirst().orElseThrow();
    }

    /** Postpones every due task of other cities, so a test's attempts work on its own rides. */
    public void onlyDueIn(String cityId) {
        jdbc.sql("""
                        UPDATE dispatch.search_tasks SET due_at = now() + interval '1 hour'
                        WHERE city_id <> :cityId AND due_at IS NOT NULL
                        """)
                .param("cityId", cityId)
                .update();
    }

    /**
     * Fires the aggregate's timer of the kind now, and no other: timers other tests left due are postponed an hour, as
     * {@link #onlyDueIn} does for tasks, so outcome counts and events come from this timer alone.
     */
    public void fire(String kind, UUID aggregateId) {
        jdbc.sql("""
                        UPDATE platform.timers SET due_at = now() + interval '1 hour'
                        WHERE due_at <= now() AND NOT (kind = :kind AND aggregate_id = :aggregateId)
                        """)
                .param("kind", kind)
                .param("aggregateId", aggregateId)
                .update();
        timers.makeDue(kind, aggregateId);
        asDispatch(() -> {
            timers.fireAllDue();
            return null;
        });
    }

    public TimerFiring timers() {
        return timers;
    }

    /** The pending offer of the ride, if any. */
    public UUID pendingOffer(UUID rideId) {
        return jdbc.sql("SELECT id FROM dispatch.offers WHERE ride_id = :rideId AND status = 'PENDING'")
                .param("rideId", rideId)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    public String offerStatus(UUID offerId) {
        return jdbc.sql("SELECT status FROM dispatch.offers WHERE id = :id").param("id", offerId)
                .query(String.class).single();
    }

    public String rideStatus(UUID rideId) {
        return jdbc.sql("SELECT status FROM ride.rides WHERE id = :id").param("id", rideId).query(String.class).single();
    }

    /** Every invariant check's violations in the city, prefixed with the check's number. */
    public List<String> violations(String cityId) {
        List<String> violations = new ArrayList<>();
        checks.forEach(check -> check.violations(cityId).forEach(violation -> violations.add(check.id() + ": "
                + violation)));
        return violations;
    }

    public static <T> T asApi(Supplier<T> work) {
        return in("api", work);
    }

    public static <T> T asDispatch(Supplier<T> work) {
        return in("dispatch", work);
    }

    private static <T> T in(String role, Supplier<T> work) {
        List<T> result = new ArrayList<>(1);
        LogContext.run(Map.of(LogContext.ROLE, role), () -> result.add(work.get()));
        return result.getFirst();
    }
}

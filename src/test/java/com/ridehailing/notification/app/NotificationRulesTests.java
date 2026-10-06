package com.ridehailing.notification.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.dispatch.events.DriverWentOffline;
import com.ridehailing.payment.events.ChargeFailed;
import com.ridehailing.payment.events.ChargeSucceeded;
import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.EventEnvelope;
import com.ridehailing.ride.events.DriverArrived;
import com.ridehailing.ride.events.DriverAssigned;
import com.ridehailing.ride.events.DriverUnassigned;
import com.ridehailing.ride.events.RideCancelled;
import com.ridehailing.ride.events.RideNotMatched;
import com.ridehailing.ride.events.TripCompleted;
import com.ridehailing.ride.events.TripStarted;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.Money;
import com.ridehailing.support.EventContract;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * LLD §15.4: who is told what, by event. The fixtures are checked against their producers' schemas first (§15.3), so
 * the consumers read events as the producers write them.
 */
class NotificationRulesTests extends NotificationTest {

    private static final Instant AT = Instant.parse("2026-10-04T08:30:00Z");
    private static final Money FARE = new Money(25_900, "INR");

    @Autowired
    RideNotifications rides;

    @Autowired
    PaymentNotifications payments;

    @Autowired
    DriverNotifications drivers;

    private final UUID ride = Ids.newId();
    private final UUID rider = Ids.newId();
    private final UUID driver = Ids.newId();

    @Test
    void theRiderIsToldOfTheAssignmentTheArrivalAndTheStart() {
        EventEnvelope assigned = handled(rides, "DriverAssigned", new DriverAssigned(ride, rider, driver, Ids.newId(),
                Ids.newId(), 240, 0, AT));
        EventEnvelope arrived = handled(rides, "DriverArrived", new DriverArrived(ride, rider, driver, 40, AT));
        EventEnvelope started = handled(rides, "TripStarted", new TripStarted(ride, rider, driver, AT));

        assertThat(told(assigned)).containsExactly("DRIVER_ASSIGNED to the rider");
        assertThat(told(arrived)).containsExactly("DRIVER_ARRIVED to the rider");
        assertThat(told(started)).containsExactly("TRIP_STARTED to the rider");
        for (EventEnvelope event : List.of(assigned, arrived, started)) {
            assertThat(payloads(event)).containsExactly(json("{\"ride_id\": \"%s\"}".formatted(ride)));
            assertThat(toldBy(event.eventId())).allSatisfy(told -> assertThat(told.rideId()).isEqualTo(ride));
        }
    }

    @Test
    void aCompletedTripTellsBothWithTheFare() {
        EventEnvelope completed = handled(rides, "TripCompleted", new TripCompleted(ride, rider, driver, "blr",
                "MINI", FARE, new Money(4_934, "INR"), Ids.newId(), "CARD", AT));

        assertThat(told(completed)).containsExactly("TRIP_COMPLETED to the driver", "TRIP_COMPLETED to the rider");
        assertThat(payloads(completed)).containsOnly(json("""
                {"ride_id": "%s", "fare": {"amount_paise": 25900, "currency": "INR"}}
                """.formatted(ride)));
    }

    @Test
    void theDriverIsToldOfAnUnassignmentOnlyWhenTheyDidNotChooseIt() {
        EventEnvelope cancelled = handled(rides, "DriverUnassigned", new DriverUnassigned(ride, rider, driver,
                "DRIVER_CANCELLED", 2, AT));
        EventEnvelope unreachable = handled(rides, "DriverUnassigned", new DriverUnassigned(ride, rider, driver,
                "DRIVER_UNREACHABLE", 3, AT));

        assertThat(told(cancelled)).containsExactly("DRIVER_UNASSIGNED to the rider");
        assertThat(payloads(cancelled)).containsExactly(json("""
                {"ride_id": "%s", "reason": "DRIVER_CANCELLED"}""".formatted(ride)));
        assertThat(told(unreachable)).containsExactly("DRIVER_UNASSIGNED to the driver",
                "DRIVER_UNASSIGNED to the rider");
        assertThat(payloads(unreachable)).containsOnly(json("""
                {"ride_id": "%s", "reason": "DRIVER_UNREACHABLE"}""".formatted(ride)));
    }

    @Test
    void aCancellationTellsWhoeverDidNotCancel() {
        EventEnvelope byRider = handled(rides, "RideCancelled", cancelled("RIDER", driver, null, null));
        EventEnvelope whileSearching = handled(rides, "RideCancelled", cancelled("RIDER", null, null, null));
        EventEnvelope noShow = handled(rides, "RideCancelled", cancelled("DRIVER", driver, "NO_SHOW",
                new RideCancelled.Fee("NO_SHOW_FEE", new Money(5_000, "INR"), new Money(1_000, "INR"), Ids.newId())));
        EventEnvelope byOperations = handled(rides, "RideCancelled", cancelled("SYSTEM", driver, "Road closed", null));
        EventEnvelope byOperationsWhileSearching = handled(rides, "RideCancelled", cancelled("SYSTEM", null, null,
                null));

        assertThat(told(byRider)).containsExactly("RIDE_CANCELLED to the driver");
        assertThat(payloads(byRider)).containsExactly(json("""
                {"ride_id": "%s", "cancelled_by": "RIDER"}""".formatted(ride)));
        assertThat(told(whileSearching)).isEmpty();
        assertThat(told(noShow)).containsExactly("RIDE_CANCELLED to the rider");
        assertThat(payloads(noShow)).as("the fee as the rider sees it: no commission, no rule")
                .containsExactly(json("""
                        {"ride_id": "%s", "cancelled_by": "DRIVER", "reason": "NO_SHOW",
                         "fee": {"purpose": "NO_SHOW_FEE", "amount": {"amount_paise": 5000, "currency": "INR"}}}
                        """.formatted(ride)));
        assertThat(told(byOperations)).containsExactly("RIDE_CANCELLED to the driver", "RIDE_CANCELLED to the rider");
        assertThat(payloads(byOperations)).containsOnly(json("""
                {"ride_id": "%s", "cancelled_by": "SYSTEM", "reason": "Road closed"}""".formatted(ride)));
        assertThat(told(byOperationsWhileSearching)).containsExactly("RIDE_CANCELLED to the rider");
    }

    @Test
    void theRiderIsToldNoDriverWasFound() {
        EventEnvelope notMatched = handled(rides, "RideNotMatched", new RideNotMatched(ride, rider, "blr", "MINI",
                180, AT));

        assertThat(told(notMatched)).containsExactly("NO_DRIVER_FOUND to the rider");
        assertThat(payloads(notMatched)).containsExactly(json("{\"ride_id\": \"%s\"}".formatted(ride)));
    }

    @Test
    void theRiderIsToldHowEachChargeEnded() {
        UUID charge = Ids.newId();
        EventEnvelope succeeded = handled(payments, "ChargeSucceeded", new ChargeSucceeded(charge, ride, rider, driver,
                "FARE", FARE, "CARD", Ids.newId(), AT));
        EventEnvelope failed = handled(payments, "ChargeFailed", new ChargeFailed(charge, ride, rider,
                "CANCELLATION_FEE", new Money(5_000, "INR"), "UPI", "DECLINED", Ids.newId(), AT));

        assertThat(told(succeeded)).containsExactly("PAYMENT_SUCCEEDED to the rider");
        assertThat(payloads(succeeded)).containsExactly(json("""
                {"ride_id": "%s", "charge_id": "%s", "purpose": "FARE",
                 "amount": {"amount_paise": 25900, "currency": "INR"}}""".formatted(ride, charge)));
        assertThat(told(failed)).containsExactly("PAYMENT_FAILED to the rider");
        assertThat(payloads(failed)).containsExactly(json("""
                {"ride_id": "%s", "charge_id": "%s", "purpose": "CANCELLATION_FEE",
                 "amount": {"amount_paise": 5000, "currency": "INR"}, "failure_code": "DECLINED"}
                """.formatted(ride, charge)));
        assertThat(toldBy(succeeded.eventId())).allSatisfy(told -> assertThat(told.rideId()).isEqualTo(ride));
    }

    @Test
    void theDriverIsToldOnlyOfGoingOfflineForReasonsTheyDidNotChoose() {
        for (String reason : List.of("SILENT", "UNREACHABLE", "UNRESPONSIVE")) {
            EventEnvelope offline = handled(drivers, "DriverWentOffline", new DriverWentOffline(driver, "blr", reason,
                    600, AT));

            assertThat(told(offline)).as(reason).containsExactly("DRIVER_WENT_OFFLINE to the driver");
            assertThat(payloads(offline)).containsExactly(json("{\"reason\": \"%s\"}".formatted(reason)));
            assertThat(toldBy(offline.eventId())).allSatisfy(told -> assertThat(told.rideId()).isNull());
        }
        for (String reason : List.of("DRIVER", "SUSPENDED")) {
            assertThat(told(handled(drivers, "DriverWentOffline", new DriverWentOffline(driver, "blr", reason, 600,
                    AT)))).as(reason).isEmpty();
        }
    }

    @Test
    void theDriverIsToldOfASuspension() {
        JsonNode suspended = json("""
                {"driver_id": "%s", "reason": "Repeated safety complaints", "by": "%s", "at": "%s"}
                """.formatted(driver, Ids.newId(), AT));

        EventEnvelope event = handled(drivers, "DriverSuspended", suspended);

        assertThat(told(event)).containsExactly("DRIVER_SUSPENDED to the driver");
        assertThat(payloads(event)).containsExactly(json("{\"reason\": \"Repeated safety complaints\"}"));
    }

    @Test
    void eachNotificationHasOnePushDeliveryDueAtOnce() {
        EventEnvelope completed = handled(rides, "TripCompleted", new TripCompleted(ride, rider, driver, "blr",
                "MINI", FARE, new Money(4_934, "INR"), Ids.newId(), "CASH", AT));

        assertThat(toldBy(completed.eventId())).hasSize(2).allSatisfy(told -> {
            assertThat(told.deliveries()).isEqualTo(1);
            assertThat(told.channel()).isEqualTo("PUSH");
            assertThat(told.status()).isEqualTo("PENDING");
            assertThat(told.attempts()).isZero();
            assertThat(told.lastError()).isNull();
            assertThat(told.dueIn()).isLessThanOrEqualTo(Duration.ZERO);
        });
    }

    @Test
    void anEventHandledAgainTellsNobodyTwice() {
        EventEnvelope unreachable = handled(rides, "DriverUnassigned", new DriverUnassigned(ride, rider, driver,
                "DRIVER_UNREACHABLE", 2, AT));

        transactions.run(() -> rides.handle(unreachable));

        assertThat(toldBy(unreachable.eventId())).hasSize(2)
                .allSatisfy(told -> assertThat(told.deliveries()).isEqualTo(1));
    }

    @Test
    void theDeliveriesAreScheduledOnTheConfiguredBackoff() {
        assertThat(properties.backoff()).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(5),
                Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(5));
        assertThat(properties.lease()).isEqualTo(Duration.ofSeconds(30));
    }

    /** Handles the event in a transaction, as a delivery would, after checking it against its schema. */
    private EventEnvelope handled(EventConsumer consumer, String type, Object payload) {
        JsonNode body = payload instanceof JsonNode node ? node : json.valueToTree(payload);
        EventContract.assertPayloadConforms(type, 1, body);
        EventEnvelope event = new EventEnvelope(Ids.newId(), type, 1, "ride", ride, 1, ride, AT, "ride/api",
                ride.toString(), null, null, body);
        transactions.run(() -> consumer.handle(event));
        return event;
    }

    private RideCancelled cancelled(String by, UUID driverId, String reason, RideCancelled.Fee fee) {
        return new RideCancelled(ride, rider, driverId, "blr", "CANCELLED_BY_" + by, by, reason, fee, Ids.newId(),
                "CARD", AT);
    }

    private List<String> told(EventEnvelope event) {
        return toldBy(event.eventId()).stream().map(told -> told.kind() + " to the " + who(told.recipientId()))
                .sorted().toList();
    }

    private List<JsonNode> payloads(EventEnvelope event) {
        return toldBy(event.eventId()).stream().map(Told::payload).toList();
    }

    private String who(UUID recipient) {
        if (recipient.equals(rider)) {
            return "rider";
        }
        return recipient.equals(driver) ? "driver" : recipient.toString();
    }

    private JsonNode json(String text) {
        return json.readTree(text);
    }
}

package com.ridehailing.payment.app;

import com.ridehailing.payment.app.ChargeCreation.Due;
import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.EventEnvelope;
import com.ridehailing.shared.Money;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code payment.charges} (LLD §15.2): {@code TripCompleted} charges the fare, a {@code RideCancelled} with a fee
 * charges the fee. Ride events arrive as JSON, since payment doesn't depend on ride (ADR-019).
 */
@Component
class ChargeConsumer implements EventConsumer {

    static final String NAME = "payment.charges";
    static final String TRIP_COMPLETED = "TripCompleted";
    static final String RIDE_CANCELLED = "RideCancelled";

    private final ChargeCreation creation;

    ChargeConsumer(ChargeCreation creation) {
        this.creation = creation;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(TRIP_COMPLETED, RIDE_CANCELLED);
    }

    @Override
    public void handle(EventEnvelope event) {
        JsonNode ride = event.payload();
        if (TRIP_COMPLETED.equals(event.eventType())) {
            creation.create(due(ride, Earnings.FARE, money(ride.get("fare")), money(ride.get("commission"))));
            return;
        }
        if (ride.hasNonNull("fee")) {
            JsonNode fee = ride.get("fee");
            creation.create(due(ride, fee.get("purpose").asString(), money(fee.get("amount")),
                    money(fee.get("commission"))));
        }
    }

    private static Due due(JsonNode ride, String purpose, Money amount, Money commission) {
        return new Due(uuid(ride, "ride_id"), uuid(ride, "rider_id"), uuid(ride, "driver_id"),
                ride.get("city_id").asString(), purpose, amount, commission, uuid(ride, "payment_method_id"),
                ride.get("payment_method_type").asString());
    }

    static UUID uuid(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : UUID.fromString(value.asString());
    }

    static Money money(JsonNode money) {
        return new Money(money.get("amount_paise").asLong(), money.get("currency").asString());
    }

    static Instant instant(JsonNode node, String field) {
        return Instant.parse(node.get(field).asString());
    }
}

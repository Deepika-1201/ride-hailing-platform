package com.ridehailing.payment.app;

import static com.ridehailing.payment.app.ChargeConsumer.instant;
import static com.ridehailing.payment.app.ChargeConsumer.money;
import static com.ridehailing.payment.app.ChargeConsumer.uuid;

import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.EventEnvelope;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** {@code payment.earnings} (LLD §15.2): every fare's earnings row, cash or online (§11.10). */
@Component
class EarningsConsumer implements EventConsumer {

    static final String NAME = "payment.earnings";

    private final Earnings earnings;

    EarningsConsumer(Earnings earnings) {
        this.earnings = earnings;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(ChargeConsumer.TRIP_COMPLETED);
    }

    @Override
    public void handle(EventEnvelope event) {
        JsonNode trip = event.payload();
        earnings.fare(uuid(trip, "ride_id"), uuid(trip, "driver_id"), trip.get("city_id").asString(),
                money(trip.get("fare")), money(trip.get("commission")),
                ChargeCreation.CASH.equals(trip.get("payment_method_type").asString()), instant(trip, "completed_at"));
    }
}

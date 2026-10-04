package com.ridehailing.payment.app;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.shared.Money;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.TestDrivers;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestRides.AssignedRide;
import com.ridehailing.support.TestUsers;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Driver earnings (FR-D4, LLD §11.8, §11.10): per day in the city's time zone, zeros included, with totals. */
class EarningsTests extends PaymentTest {

    private static final String EARNINGS = "/v1/drivers/me/earnings";

    @Autowired
    private Earnings earnings;

    @Autowired
    private TestDrivers testDrivers;

    @Autowired
    private TestUsers users;

    @Test
    void eachDayInTheCitysTimeZoneWithZerosAndTotals() {
        TestDriver driver = testDrivers.create(city, "MINI");
        // 18:29 UTC is 23:59 in Kolkata, 18:31 UTC is 00:01 the next day.
        fare(driver, Instant.parse("2026-09-01T18:29:00Z"), 20_000, 4_000, true);
        fare(driver, Instant.parse("2026-09-01T18:31:00Z"), 10_000, 2_000, false);
        fare(driver, Instant.parse("2026-09-03T05:00:00Z"), 15_000, 3_000, false);

        JsonNode answer = assertAnswered("GET", EARNINGS,
                getAs(driver.authorization(), EARNINGS + "?from=2026-09-01&to=2026-09-04"), 200);

        assertThat(answer.get("from").asString()).isEqualTo("2026-09-01");
        assertThat(answer.get("days").valueStream().map(day -> day.get("date").asString() + " "
                + day.get("rides").asInt() + " " + day.get("gross").get("amount_paise").asLong() + " "
                + day.get("net").get("amount_paise").asLong() + " "
                + day.get("cash_collected").get("amount_paise").asLong()))
                .containsExactly("2026-09-01 1 20000 16000 20000", "2026-09-02 1 10000 8000 0",
                        "2026-09-03 1 15000 12000 0", "2026-09-04 0 0 0 0");
        JsonNode totals = answer.get("totals");
        assertThat(totals.get("rides").asInt()).isEqualTo(3);
        assertThat(totals.get("gross").get("amount_paise").asLong()).isEqualTo(45_000);
        assertThat(totals.get("commission").get("amount_paise").asLong()).isEqualTo(9_000);
        assertThat(totals.get("net").get("amount_paise").asLong()).isEqualTo(36_000);
        assertThat(totals.get("cash_collected").get("amount_paise").asLong()).isEqualTo(20_000);
        assertThat(totals.get("gross").get("currency").asString()).isEqualTo("INR");
    }

    @Test
    void feesAndTheirRefundsCountOnTheDayTheMoneyMoved() {
        AssignedRide ride = cancelledWithFee(riderWith("tok_ok"));
        send();
        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();

        JsonNode day = assertAnswered("GET", EARNINGS, getAs(ride.driver().authorization(),
                EARNINGS + "?from=" + today + "&to=" + today), 200).get("days").get(0);

        assertThat(day.get("rides").asInt()).as("a fee is not a ride").isZero();
        assertThat(day.get("gross").get("amount_paise").asLong()).isEqualTo(5_000);
        assertThat(day.get("commission").get("amount_paise").asLong()).isEqualTo(1_000);
        assertThat(day.get("net").get("amount_paise").asLong()).isEqualTo(4_000);
    }

    @Test
    void aRequestCoversAtMost31DaysOfTheDriversOwnEarnings() {
        TestDriver driver = testDrivers.create(city, "MINI");
        TestDriver other = testDrivers.create(city, "MINI");
        fare(other, Instant.parse("2026-09-10T05:00:00Z"), 20_000, 4_000, false);

        JsonNode month = assertAnswered("GET", EARNINGS,
                getAs(driver.authorization(), EARNINGS + "?from=2026-09-01&to=2026-10-01"), 200);

        assertThat(month.get("days")).hasSize(31);
        assertThat(month.get("totals").get("gross").get("amount_paise").asLong()).isZero();
        assertThat(month.get("totals").get("gross").get("currency").asString()).isEqualTo("INR");
        assertThat(month.get("days").get(30).get("net").get("currency").asString()).isEqualTo("INR");
        for (String query : new String[] {"?from=2026-09-01&to=2026-10-02", "?from=2026-09-02&to=2026-09-01",
                "?from=2026-09-01", "?from=2026-09-01&to=soon"}) {
            assertProblem("GET", EARNINGS, getAs(driver.authorization(), EARNINGS + query), 400,
                    "VALIDATION_FAILED");
        }
        assertProblem("GET", EARNINGS, getAs(users.create(UserRole.RIDER).authorization(),
                EARNINGS + "?from=2026-09-01&to=2026-09-01"), 403, "FORBIDDEN");
    }

    private void fare(TestDriver driver, Instant completedAt, long fare, long commission, boolean cash) {
        transactions.run(() -> earnings.fare(UUID.randomUUID(), driver.id(), city.id(), new Money(fare, "INR"),
                new Money(commission, "INR"), cash, completedAt));
    }
}

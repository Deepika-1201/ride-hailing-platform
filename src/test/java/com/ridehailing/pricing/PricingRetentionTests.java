package com.ridehailing.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.pricing.PricingApi.QuoteRequest;
import com.ridehailing.pricing.app.PricingRetention;
import com.ridehailing.shared.Ids;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestPrices;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** LLD §5.7: unused quotes go 24 h after they expire, used ones after 30 days. */
class PricingRetentionTests extends IntegrationTest {

    @Autowired
    private PricingApi pricing;

    @Autowired
    private PricingRetention retention;

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void oldQuotesAreDeletedAndRecentOnesKept() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        UUID unusedExpiredADayAgo = quote(city, "24 hours 6 minutes", false);
        UUID unusedExpiredAlmostADayAgo = quote(city, "24 hours 4 minutes", false);
        UUID usedMonthsAgo = quote(city, "30 days 1 minute", true);
        UUID usedWeeksAgo = quote(city, "29 days", true);
        UUID fresh = quote(city, "0 seconds", false);

        retention.purge();

        assertThat(jdbc.sql("SELECT id FROM pricing.quotes WHERE id IN (:ids)")
                .param("ids", List.of(unusedExpiredADayAgo, unusedExpiredAlmostADayAgo, usedMonthsAgo, usedWeeksAgo,
                        fresh))
                .query(UUID.class).list())
                .containsExactlyInAnyOrder(unusedExpiredAlmostADayAgo, usedWeeksAgo, fresh);
    }

    /** A quote created {@code age} ago (by the database clock), used at once if {@code used}. */
    private UUID quote(TestCity city, String age, boolean used) {
        UUID id = pricing.quote(Ids.newId(), new QuoteRequest(city.at(0.1, 0.1), city.at(0.2, 0.2), "MINI")).id();
        jdbc.sql("""
                        UPDATE pricing.quotes
                        SET created_at = created_at - CAST(:age AS interval),
                            expires_at = expires_at - CAST(:age AS interval),
                            used_by_ride_id = CASE WHEN :used THEN gen_random_uuid() END,
                            used_at = CASE WHEN :used THEN created_at - CAST(:age AS interval) END
                        WHERE id = :id
                        """)
                .param("age", age)
                .param("used", used)
                .param("id", id)
                .update();
        return id;
    }
}

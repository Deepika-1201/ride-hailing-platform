package com.ridehailing.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Transactions;
import com.ridehailing.pricing.PricingApi.ConsumedQuote;
import com.ridehailing.pricing.PricingApi.QuoteRequest;
import com.ridehailing.pricing.PricingApi.QuoteView;
import com.ridehailing.shared.Ids;
import com.ridehailing.support.Eventually;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.Postgis;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestPrices;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;

/** LLD §7.2: a booking uses its rider's unexpired quote exactly once. */
class QuoteConsumptionTests extends IntegrationTest {

    @Autowired
    private PricingApi pricing;

    @Autowired
    private Transactions transactions;

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private JdbcClient jdbc;

    private final UUID rider = Ids.newId();
    private QuoteView quote;

    @BeforeEach
    void quote() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        quote = pricing.quote(rider, new QuoteRequest(city.at(0.1, 0.1), city.at(0.2, 0.2), "MINI"));
    }

    @Test
    void theRiderUsesTheirQuoteOnce() {
        UUID ride = Ids.newId();

        ConsumedQuote consumed = consume(quote.id(), rider, ride);

        assertThat(consumed.fare()).isEqualTo(quote.fare().total());
        assertThat(consumed.distanceM()).isEqualTo(quote.distanceM());
        assertThat(usedBy(quote.id())).isEqualTo(ride);
        assertConflict(() -> consume(quote.id(), rider, Ids.newId()), "QUOTE_ALREADY_USED");
        assertThat(usedBy(quote.id())).isEqualTo(ride);
    }

    @Test
    void anotherRidersQuoteIsNotFoundAndStaysUnused() {
        assertThatThrownBy(() -> consume(quote.id(), Ids.newId(), Ids.newId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> consume(Ids.newId(), rider, Ids.newId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));

        assertThat(usedBy(quote.id())).isNull();
    }

    @Test
    void anExpiredQuoteCantBeUsed() {
        jdbc.sql("""
                        UPDATE pricing.quotes SET created_at = created_at - interval '6 minutes',
                                                  expires_at = expires_at - interval '6 minutes'
                        WHERE id = :id
                        """)
                .param("id", quote.id())
                .update();

        assertConflict(() -> consume(quote.id(), rider, Ids.newId()), "QUOTE_EXPIRED");
        assertThat(usedBy(quote.id())).isNull();
    }

    @Test
    void usingAQuoteJoinsTheCallersTransaction() {
        assertThatThrownBy(() -> pricing.consume(quote.id(), rider, Ids.newId()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void twoBookingsRacingForOneQuoteGetItOnceBetweenThem() throws Exception {
        List<Future<String>> outcomes = new ArrayList<>();
        try (Connection blocker = Postgis.connection(); var executor = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT 1 FROM pricing.quotes WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, quote.id());
                lock.executeQuery().close();
            }
            for (int booking = 0; booking < 2; booking++) {
                outcomes.add(executor.submit(() -> {
                    try {
                        consume(quote.id(), rider, Ids.newId());
                        return "USED";
                    } catch (ApiException e) {
                        return e.code();
                    }
                }));
            }
            Eventually.within(Duration.ofSeconds(10), () -> assertThat(sessionsWaitingForALock()).isEqualTo(2));
            blocker.rollback();
            List<String> results = new ArrayList<>();
            for (Future<String> outcome : outcomes) {
                results.add(outcome.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).containsExactlyInAnyOrder("USED", "QUOTE_ALREADY_USED");
        }
    }

    private ConsumedQuote consume(UUID quoteId, UUID riderId, UUID rideId) {
        return transactions.execute(() -> pricing.consume(quoteId, riderId, rideId));
    }

    private UUID usedBy(UUID quoteId) {
        return jdbc.sql("SELECT used_by_ride_id FROM pricing.quotes WHERE id = :id").param("id", quoteId)
                .query(UUID.class).list().getFirst();
    }

    private long sessionsWaitingForALock() {
        return jdbc.sql("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE wait_event_type = 'Lock' AND datname = current_database()
                        """)
                .query(Long.class)
                .single();
    }

    private static void assertConflict(Runnable command, String code) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.status().value()).isEqualTo(409);
        });
    }
}

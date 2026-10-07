package com.ridehailing.pricing.app;

import com.ridehailing.geography.GeographyApi;
import com.ridehailing.geography.GeographyApi.CityView;
import com.ridehailing.geography.GeographyApi.Location;
import com.ridehailing.geography.RoutingProvider;
import com.ridehailing.geography.RoutingProvider.Route;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.RateLimiter;
import com.ridehailing.pricing.PricingApi;
import com.ridehailing.pricing.app.SurgeWindows.Surge;
import com.ridehailing.pricing.db.Fare;
import com.ridehailing.pricing.db.QuoteRepository;
import com.ridehailing.pricing.db.QuoteRepository.QuoteRow;
import com.ridehailing.pricing.db.RuleRepository;
import com.ridehailing.pricing.db.RuleRepository.FareRule;
import com.ridehailing.pricing.db.RuleRepository.FeeRule;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Quotes (LLD §10.4) and their one-time use by a booking (§7.2). */
@Service
class Quotes implements PricingApi {

    static final String RATE_LIMIT = "quotes-per-rider";

    private final GeographyApi geography;
    private final RoutingProvider routing;
    private final RuleRepository ruleVersions;
    private final SurgeRuleCache surgeRules;
    private final PickupEtas pickupEtas;
    private final QuoteRepository quotes;
    private final RateLimiter rateLimiter;
    private final QuoteProperties properties;
    private final Clock clock;

    Quotes(GeographyApi geography, RoutingProvider routing, RuleRepository ruleVersions, SurgeRuleCache surgeRules,
            PickupEtas pickupEtas, QuoteRepository quotes, RateLimiter rateLimiter, QuoteProperties properties,
            Clock clock) {
        this.geography = geography;
        this.routing = routing;
        this.ruleVersions = ruleVersions;
        this.surgeRules = surgeRules;
        this.pickupEtas = pickupEtas;
        this.quotes = quotes;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public QuoteView quote(UUID riderId, QuoteRequest request) {
        rateLimiter.acquireOrReject(RATE_LIMIT, riderId.toString());
        Location location = geography.locate(request.pickup()).orElseThrow(() -> unprocessable(
                "OUTSIDE_SERVICE_AREA", "The pickup is outside every service area."));
        String cityId = location.cityId();
        if (!geography.offers(cityId, request.category())) {
            throw notAvailable();
        }
        CityView city = geography.city(cityId).orElseThrow();
        ZonedDateTime local = ZonedDateTime.ofInstant(clock.instant(), city.timeZone());
        FareRule fareRule = ruleVersions.fareInEffect(cityId, request.category()).orElseThrow(Quotes::notAvailable);
        FeeRule feeRule = ruleVersions.feeInEffect(cityId, request.category()).orElseThrow(Quotes::notAvailable);
        Route route = routing.route(request.pickup(), request.dropoff(), local).orElseThrow(() -> unprocessable(
                "ROUTE_NOT_FOUND", "There is no route between the pickup and the drop-off."));
        Surge surge = SurgeWindows.at(surgeRules.of(cityId), location.zoneId(), local);
        Fare fare = FareCalculator.calculate(fareRule, route.distanceM(), route.durationS(), surge.multiplier());
        Integer pickupEta = pickupEtas.estimate(cityId, request.category(), request.pickup(), local).orElse(null);

        QuoteRow quote = quotes.insert(new QuoteRow(Ids.newId(), riderId, cityId, request.category(),
                request.pickup(), request.dropoff(), location.zoneId(), route.distanceM(), route.durationS(),
                route.source().name(), fareRule.id(), feeRule.id(), surge.multiplier(), surge.source().name(), fare,
                fareRule.currency(), pickupEta, null, null, null), properties.ttl());
        return view(quote);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ConsumedQuote consume(UUID quoteId, UUID riderId, UUID rideId) {
        QuoteRow quote = quotes.use(quoteId, riderId, rideId).orElseThrow(() -> {
            QuoteRow unusable = quotes.find(quoteId).filter(found -> found.riderId().equals(riderId))
                    .orElseThrow(ApiException::notFound);
            return unusable.usedByRideId() != null
                    ? new ApiException(HttpStatus.CONFLICT, "QUOTE_ALREADY_USED", "The quote was used already.")
                    : new ApiException(HttpStatus.CONFLICT, "QUOTE_EXPIRED", "The quote expired; get a new one.");
        });
        return new ConsumedQuote(quote.id(), quote.cityId(), quote.category(), quote.pickup(), quote.dropoff(),
                quote.pickupZone(), quote.distanceM(), quote.durationS(), money(quote.fare().total(), quote),
                money(quote.fare().commission(), quote), quote.feeRuleId(), breakdown(quote));
    }

    @Override
    public FeeTerms feeRule(UUID feeRuleId) {
        FeeRule rule = ruleVersions.feeRule(feeRuleId).orElseThrow(() -> new IllegalStateException(
                "Fee rule " + feeRuleId + " doesn't exist, though versions are never deleted"));
        return new FeeTerms(rule.id(), rule.cancellationFeePaise(), rule.noShowFeePaise(),
                Duration.ofSeconds(rule.freeCancelWindowS()), Duration.ofSeconds(rule.lateGraceS()),
                Duration.ofSeconds(rule.pickupWaitS()), rule.commissionBp(), rule.currency());
    }

    private static QuoteView view(QuoteRow quote) {
        return new QuoteView(quote.id(), quote.cityId(), quote.category(), quote.pickup(), quote.dropoff(),
                quote.distanceM(), quote.durationS(), breakdown(quote), quote.surgeMultiplier(), quote.pickupEtaS(),
                quote.createdAt(), quote.expiresAt());
    }

    private static FareBreakdown breakdown(QuoteRow quote) {
        Fare fare = quote.fare();
        return new FareBreakdown(money(fare.base(), quote), money(fare.distance(), quote),
                money(fare.time(), quote), money(fare.surge(), quote), money(fare.minimumTopup(), quote),
                money(fare.bookingFee(), quote), money(fare.tax(), quote), money(fare.rounding(), quote),
                money(fare.total(), quote));
    }

    private static Money money(long paise, QuoteRow quote) {
        return new Money(paise, quote.currency());
    }

    private static ApiException notAvailable() {
        return unprocessable("CATEGORY_NOT_AVAILABLE", "This category can't be booked here now.");
    }

    private static ApiException unprocessable(String code, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, detail);
    }
}

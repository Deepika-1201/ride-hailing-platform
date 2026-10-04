package com.ridehailing.payment.app;

import com.ridehailing.geography.GeographyApi;
import com.ridehailing.payment.db.ChargeRepository.ChargeRow;
import com.ridehailing.payment.db.EarningsRepository;
import com.ridehailing.payment.db.EarningsRepository.DaySum;
import com.ridehailing.payment.db.EarningsRepository.NewEarning;
import com.ridehailing.payment.db.RefundRepository.RefundRow;
import com.ridehailing.platform.ApiException;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Driver earnings (FR-D4, LLD §11.8, §11.10): rows written as money moves, summed per day in the city's time zone. */
@Service
public class Earnings {

    static final String FARE = "FARE";
    static final String ADJUSTMENT = "ADJUSTMENT";
    static final int MAX_DAYS_AFTER_FROM = 30;

    private final EarningsRepository earnings;
    private final GeographyApi geography;
    private final PaymentProperties properties;

    Earnings(EarningsRepository earnings, GeographyApi geography, PaymentProperties properties) {
        this.earnings = earnings;
        this.geography = geography;
        this.properties = properties;
    }

    /** A completed trip; a cash fare is collected by the driver. */
    void fare(UUID rideId, UUID driverId, String cityId, Money fare, Money commission, boolean cash,
            Instant completedAt) {
        earnings.insert(new NewEarning(Ids.newId(), driverId, rideId, FARE, rideId, fare.amountPaise(),
                commission.amountPaise(), cash ? fare.amountPaise() : 0, fare.currency(), completedAt,
                day(cityId, completedAt)));
    }

    /** A fee charge that just succeeded, earned when it was paid; only for a ride with a driver. */
    void fee(ChargeRow charge) {
        if (charge.driverId() == null || FARE.equals(charge.purpose())) {
            return;
        }
        earnings.insert(new NewEarning(Ids.newId(), charge.driverId(), charge.rideId(), charge.purpose(),
                charge.id(), charge.amountPaise(), charge.commissionPaise(), 0, charge.currency(), charge.updatedAt(),
                day(charge.cityId(), charge.updatedAt())));
    }

    /** An operations refund of a fee that just succeeded reverses the driver's share in proportion (§11.6). */
    void adjustment(ChargeRow charge, RefundRow refund) {
        if (charge.driverId() == null || FARE.equals(charge.purpose())) {
            return;
        }
        long commission = BigDecimal.valueOf(charge.commissionPaise())
                .multiply(BigDecimal.valueOf(refund.amountPaise()))
                .divide(BigDecimal.valueOf(charge.amountPaise()), 0, RoundingMode.HALF_UP)
                .longValueExact();
        earnings.insert(new NewEarning(Ids.newId(), charge.driverId(), charge.rideId(), ADJUSTMENT, refund.id(),
                -refund.amountPaise(), -commission, 0, charge.currency(), refund.completedAt(),
                day(charge.cityId(), refund.completedAt())));
    }

    /** Every day from {@code from} to {@code to}, zeros included, with totals; at most 31 days. */
    public EarningsView between(UUID driverId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.invalid("to", "must not be before from");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS_AFTER_FROM) {
            throw ApiException.invalid("to", "must be at most " + MAX_DAYS_AFTER_FROM + " days after from");
        }
        List<DaySum> sums = earnings.days(driverId, from, to);
        String currency = currency(sums);
        Map<LocalDate, DaySum> byDay = sums.stream().collect(Collectors.toMap(DaySum::day, Function.identity()));
        List<EarningsDay> days = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            DaySum sum = byDay.get(day);
            days.add(sum == null ? EarningsDay.zero(day, currency) : EarningsDay.of(sum));
        }
        Totals totals = new Totals(sums.stream().mapToInt(DaySum::rides).sum(),
                money(sums.stream().mapToLong(DaySum::grossPaise).sum(), currency),
                money(sums.stream().mapToLong(DaySum::commissionPaise).sum(), currency),
                money(sums.stream().mapToLong(DaySum::netPaise).sum(), currency),
                money(sums.stream().mapToLong(DaySum::cashCollectedPaise).sum(), currency));
        return new EarningsView(from, to, days, totals);
    }

    private String currency(List<DaySum> sums) {
        Set<String> currencies = sums.stream().map(DaySum::currency).collect(Collectors.toSet());
        if (currencies.size() > 1) {
            throw new IllegalStateException("Earnings in more than one currency: " + currencies);
        }
        return currencies.isEmpty() ? properties.currency() : currencies.iterator().next();
    }

    private LocalDate day(String cityId, Instant at) {
        GeographyApi.CityView city = geography.city(cityId)
                .orElseThrow(() -> new IllegalStateException("No city " + cityId));
        return at.atZone(city.timeZone()).toLocalDate();
    }

    private static Money money(long paise, String currency) {
        return new Money(paise, currency);
    }

    /** The Earnings schema of {@code openapi.yaml}. */
    public record EarningsView(LocalDate from, LocalDate to, List<EarningsDay> days, Totals totals) {
    }

    public record EarningsDay(LocalDate date, int rides, Money gross, Money commission, Money net,
            Money cashCollected) {

        static EarningsDay of(DaySum sum) {
            return new EarningsDay(sum.day(), sum.rides(), money(sum.grossPaise(), sum.currency()),
                    money(sum.commissionPaise(), sum.currency()), money(sum.netPaise(), sum.currency()),
                    money(sum.cashCollectedPaise(), sum.currency()));
        }

        static EarningsDay zero(LocalDate day, String currency) {
            return new EarningsDay(day, 0, money(0, currency), money(0, currency), money(0, currency),
                    money(0, currency));
        }
    }

    public record Totals(int rides, Money gross, Money commission, Money net, Money cashCollected) {
    }
}

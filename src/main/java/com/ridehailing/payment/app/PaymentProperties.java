package com.ridehailing.payment.app;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code ride.payments} (LLD §20, §11.10): the executor, status checks, webhooks and the mock provider. */
@ConfigurationProperties("ride.payments")
public record PaymentProperties(
        @DefaultValue("2") int workers,
        @DefaultValue("250ms") Duration pollInterval,
        @DefaultValue("30s") Duration lease,
        @DefaultValue("3s") Duration readTimeout,
        @DefaultValue({"10s", "30s", "2m", "10m", "1h"}) List<Duration> checkSchedule,
        @DefaultValue("24h") Duration checkFor,
        @DefaultValue("2m") Duration notReceivedAfter,
        @DefaultValue("5m") Duration webhookTolerance,
        @DefaultValue("5") int breakerFailures,
        @DefaultValue("30s") Duration breakerOpenFor,
        @DefaultValue("INR") String currency,
        @DefaultValue Mock mock) {

    public PaymentProperties {
        if (checkSchedule.isEmpty()) {
            throw new IllegalArgumentException("ride.payments.check-schedule needs at least one delay");
        }
        checkSchedule = List.copyOf(checkSchedule);
    }

    /** The delay before check {@code n + 1}, once {@code n} checks were inconclusive; the last delay repeats. */
    Duration checkDelay(int checksDone) {
        return checkSchedule.get(Math.min(checksDone, checkSchedule.size() - 1));
    }

    /** §11.9. {@code webhookSecret} may be unset only in the local and test profiles, which generate one. */
    public record Mock(
            @DefaultValue("300ms") Duration latencyMedian,
            @DefaultValue("2s") Duration latencyP99,
            @DefaultValue("0.05") double declineRate,
            @DefaultValue("0.02") double timeoutRate,
            String webhookSecret,
            @DefaultValue Webhooks webhooks) {
    }

    /** {@code url} defaults to this process's own API port. */
    public record Webhooks(
            @DefaultValue("true") boolean enabled,
            String url,
            @DefaultValue("30s") Duration maxDelay,
            @DefaultValue("0.10") double duplicateRate,
            @DefaultValue("0.20") double earlyRate) {
    }
}

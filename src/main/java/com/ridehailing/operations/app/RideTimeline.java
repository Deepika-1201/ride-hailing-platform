package com.ridehailing.operations.app;

import com.ridehailing.audit.AuditLog;
import com.ridehailing.audit.AuditLog.AuditRecord;
import com.ridehailing.dispatch.DispatchQueries;
import com.ridehailing.dispatch.DispatchQueries.DecisionView;
import com.ridehailing.dispatch.DispatchQueries.OfferRecord;
import com.ridehailing.notification.NotificationApi;
import com.ridehailing.notification.NotificationApi.DeliveryView;
import com.ridehailing.notification.NotificationApi.NotificationView;
import com.ridehailing.payment.PaymentApi;
import com.ridehailing.payment.PaymentApi.ChargeView;
import com.ridehailing.payment.PaymentApi.RefundView;
import com.ridehailing.payment.PaymentApi.RidePayments;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.EventEnvelope;
import com.ridehailing.platform.EventLog;
import com.ridehailing.ride.RideOperations;
import com.ridehailing.ride.RideOperations.FlagView;
import com.ridehailing.ride.RideQueries;
import com.ridehailing.ride.RideQueries.TransitionView;
import com.ridehailing.ride.RideView;
import com.ridehailing.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * A ride's timeline (FR-O1, FR-DS6, LLD §13.5): what every module recorded about the ride, in time order, so that
 * "why did this take so long?" is answered from the data alone.
 */
@Service
public class RideTimeline {

    private final RideQueries rides;
    private final RideOperations rideOperations;
    private final DispatchQueries dispatch;
    private final EventLog events;
    private final PaymentApi payments;
    private final NotificationApi notifications;
    private final AuditLog auditLog;

    RideTimeline(RideQueries rides, RideOperations rideOperations, DispatchQueries dispatch, EventLog events,
            PaymentApi payments, NotificationApi notifications, AuditLog auditLog) {
        this.rides = rides;
        this.rideOperations = rideOperations;
        this.dispatch = dispatch;
        this.events = events;
        this.payments = payments;
        this.notifications = notifications;
        this.auditLog = auditLog;
    }

    /** {@code 404} for a ride that doesn't exist. */
    public Timeline of(UUID rideId) {
        RideView ride = rides.find(rideId).orElseThrow(ApiException::notFound);
        List<Entry> entries = new ArrayList<>();
        rides.transitions(rideId).forEach(transition -> entries.add(new Entry(transition.occurredAt(),
                Kind.TRANSITION, transition(transition), transition)));
        dispatch.decisions(rideId).forEach(decision -> entries.add(new Entry(decision.createdAt(),
                Kind.DISPATCH_DECISION, decision(decision), decision)));
        dispatch.offers(rideId).forEach(offer -> entries.add(new Entry(offer.createdAt(), Kind.OFFER, offer(offer),
                offer)));
        events.byPartitionKey(rideId).forEach(event -> entries.add(new Entry(event.occurredAt(), Kind.EVENT,
                event(event), event)));
        RidePayments paid = payments.ofRide(rideId);
        paid.charges().forEach(charge -> entries.add(new Entry(charge.createdAt(), Kind.CHARGE, charge(charge),
                charge)));
        paid.refunds().forEach(refund -> entries.add(new Entry(refund.createdAt(), Kind.REFUND, refund(refund),
                refund)));
        notifications.ofRide(rideId).forEach(notification -> entries.add(new Entry(notification.createdAt(),
                Kind.NOTIFICATION, notification(notification, ride), notification)));
        List<FlagView> flags = rideOperations.flagsOfRide(rideId);
        for (FlagView flag : flags) {
            entries.add(new Entry(flag.createdAt(), Kind.FLAG, flag.kind() + " opened", flag));
            if (flag.resolvedAt() != null) {
                entries.add(new Entry(flag.resolvedAt(), Kind.FLAG, flag.kind() + " resolved: " + flag.resolution(),
                        flag));
            }
        }
        List<AuditRecord> audited = new ArrayList<>();
        audited.addAll(auditLog.entries("charge", paid.charges().stream().map(charge -> charge.id().toString())
                .toList()));
        audited.addAll(auditLog.entries("refund", paid.refunds().stream().map(refund -> refund.id().toString())
                .toList()));
        audited.addAll(auditLog.entries("flag", flags.stream().map(flag -> flag.id().toString()).toList()));
        audited.forEach(entry -> entries.add(new Entry(entry.occurredAt(), Kind.AUDIT, audit(entry), entry)));
        // Stable, and the sources were added kind by kind: entries at one instant stay in kind order, then source order.
        entries.sort(Comparator.comparing(Entry::at));
        return new Timeline(rideId, entries);
    }

    private static String transition(TransitionView transition) {
        String summary = (transition.fromStatus() == null ? "" : transition.fromStatus() + " → ")
                + transition.toStatus() + ": " + transition.command() + " by " + transition.actorType();
        return transition.reason() == null ? summary : summary + " (" + transition.reason() + ")";
    }

    private static String decision(DecisionView decision) {
        String summary = "Attempt " + decision.attempt() + " within " + decision.radiusM() + " m: "
                + decision.outcome() + ", " + decision.detail().path("candidates").size() + " candidates";
        return decision.chosenDriverId() == null ? summary : summary + ", offered to driver "
                + decision.chosenDriverId();
    }

    private static String offer(OfferRecord offer) {
        String summary = "Offer to driver " + offer.driverId() + " at " + offer.distanceM() + " m: " + offer.status();
        if (offer.respondedAt() != null) {
            summary += " after " + Duration.between(offer.createdAt(), offer.respondedAt()).toSeconds() + " s";
        }
        if (offer.endReason() != null) {
            summary += " (" + offer.endReason() + ")";
        }
        if (offer.status().equals("EXPIRED")) {
            summary += offer.seenAt() == null ? ", never seen" : ", seen but not answered";
        }
        return summary;
    }

    private static String event(EventEnvelope event) {
        return event.eventType() + " from " + event.producer();
    }

    private static String charge(ChargeView charge) {
        String summary = charge.purpose() + " of " + money(charge.amount()) + " by " + charge.methodType() + ": "
                + charge.status();
        return charge.failureCode() == null ? summary : summary + " (" + charge.failureCode() + ")";
    }

    private static String refund(RefundView refund) {
        return "Refund of " + money(refund.amount()) + (refund.automatic() ? ", automatic" : "") + ": "
                + refund.status() + (refund.reason() == null ? "" : " (" + refund.reason() + ")");
    }

    private static String notification(NotificationView notification, RideView ride) {
        String to = notification.recipientId().equals(ride.riderId()) ? "the rider"
                : "driver " + notification.recipientId();
        String delivered = notification.deliveries().stream().map(DeliveryView::status).findFirst().orElse("no delivery");
        return notification.kind() + " to " + to + ": " + delivered;
    }

    private static String audit(AuditRecord entry) {
        String summary = entry.action() + " by " + entry.actorType();
        return entry.reason() == null ? summary : summary + " (" + entry.reason() + ")";
    }

    private static String money(Money amount) {
        return amount.currency() + " " + amount.amountPaise() / 100 + "." + String.format("%02d",
                amount.amountPaise() % 100);
    }

    /** In this order among entries at the same instant (§13.5). */
    public enum Kind {
        TRANSITION,
        DISPATCH_DECISION,
        OFFER,
        EVENT,
        CHARGE,
        REFUND,
        NOTIFICATION,
        FLAG,
        AUDIT
    }

    /** The TimelineEntry schema of {@code openapi.yaml}; {@code data} is the record the entry came from. */
    public record Entry(Instant at, Kind kind, String summary, Object data) {
    }

    /** The Timeline schema of {@code openapi.yaml}. */
    public record Timeline(UUID rideId, List<Entry> entries) {

        public Timeline {
            entries = List.copyOf(entries);
        }
    }
}

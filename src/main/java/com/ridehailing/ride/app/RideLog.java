package com.ridehailing.ride.app;

import com.ridehailing.audit.AuditEntry;
import com.ridehailing.audit.AuditLog;
import com.ridehailing.platform.LogContext;
import com.ridehailing.ride.app.RideTransitions.Command;
import com.ridehailing.ride.db.RideRepository.RideRow;
import com.ridehailing.ride.db.TransitionRepository;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.Ids;
import java.util.Locale;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** Every ride transition writes a transition row and an audit entry, in the caller's transaction (LLD §7.1). */
@Component
class RideLog {

    private final TransitionRepository transitions;
    private final AuditLog auditLog;

    RideLog(TransitionRepository transitions, AuditLog auditLog) {
        this.transitions = transitions;
        this.auditLog = auditLog;
    }

    /** {@code before} is null for the booking; {@code reason} may be null. */
    void record(RideRow before, RideRow after, Command command, Actor actor, String reason) {
        transitions.insert(Ids.newId(), after.id(), after.version(), before == null ? null : before.status(),
                after.status(), command.name(), actor.type().name(), actor.id(), reason,
                MDC.get(LogContext.REQUEST_ID));
        auditLog.record(new AuditEntry(actor, "ride." + command.name().toLowerCase(Locale.ROOT), "ride",
                after.id().toString(), reason, before == null ? null : Map.of("status", before.status().name()),
                Map.of("status", after.status().name())));
    }
}

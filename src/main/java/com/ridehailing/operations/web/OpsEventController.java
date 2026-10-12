package com.ridehailing.operations.web;

import com.ridehailing.audit.AuditEntry;
import com.ridehailing.audit.AuditLog;
import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Cursor;
import com.ridehailing.platform.EventRecovery;
import com.ridehailing.platform.EventRecovery.DeadLetterView;
import com.ridehailing.platform.EventRecovery.ReplayView;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.IdempotentCall;
import com.ridehailing.platform.Transactions;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.Page;
import com.ridehailing.shared.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@ApiController
@AllowedRoles({UserRole.OPS, UserRole.ADMIN})
@RequestMapping("/v1/ops")
class OpsEventController {

    private final EventRecovery recovery;
    private final Idempotency idempotency;
    private final Transactions transactions;
    private final AuditLog audit;

    OpsEventController(EventRecovery recovery, Idempotency idempotency, Transactions transactions, AuditLog audit) {
        this.recovery = recovery;
        this.idempotency = idempotency;
        this.transactions = transactions;
        this.audit = audit;
    }

    @GetMapping("/dead-letters")
    Page<DeadLetterView> deadLetters(@RequestParam(required = false) String consumer,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return recovery.deadLetters(consumer, Cursor.decode(cursor), Cursor.limit(limit));
    }

    @PostMapping("/dead-letters/{deadLetterId}/redrive")
    ResponseEntity<?> redrive(Caller caller, @PathVariable UUID deadLetterId,
            @RequestHeader(value = Idempotency.HEADER, required = false) String key) {
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key,
                "POST /v1/ops/dead-letters/" + deadLetterId + "/redrive", Map.of()), () -> transactions.execute(() -> {
                    DeadLetterView letter = recovery.redrive(deadLetterId);
                    audit.record(new AuditEntry(staff(caller), "dead_letter.redrive", "dead_letter", deadLetterId.toString(),
                            null, null, Map.of("consumer", letter.consumer(), "redriven_at", letter.redrivenAt())));
                    return ResponseEntity.ok(letter);
                }));
    }

    @PostMapping("/replays")
    ResponseEntity<?> replay(Caller caller, @RequestHeader(value = Idempotency.HEADER, required = false) String key,
            @Valid @RequestBody ReplayBody body) {
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key, "POST /v1/ops/replays", body),
                () -> transactions.execute(() -> {
                    ReplayView replay = recovery.startReplay(body.consumer(), body.from(), body.to(), Boolean.TRUE.equals(body.reprocess()));
                    audit.record(new AuditEntry(staff(caller), "events.replay", "event_replay", replay.id().toString(), null, null,
                            Map.of("consumer", replay.consumer(), "from", replay.from(), "to", replay.to(),
                                    "reprocess", replay.reprocess(), "total_events", replay.totalEvents())));
                    return ResponseEntity.accepted().body(replay);
                }));
    }

    @GetMapping("/replays/{replayId}")
    ReplayView replay(@PathVariable UUID replayId) {
        return recovery.replay(replayId);
    }

    @PostMapping("/replays/{replayId}/retry")
    ResponseEntity<?> retry(Caller caller, @PathVariable UUID replayId,
            @RequestHeader(value = Idempotency.HEADER, required = false) String key) {
        return idempotency.execute(new IdempotentCall(caller.userId().toString(), key,
                "POST /v1/ops/replays/" + replayId + "/retry", Map.of()), () -> transactions.execute(() -> {
                    ReplayView replay = recovery.retryReplay(replayId);
                    audit.record(new AuditEntry(staff(caller), "events.replay.retry", "event_replay", replay.id().toString(),
                            null, null, Map.of("consumer", replay.consumer(), "events", replay.events(), "reprocess", replay.reprocess())));
                    return ResponseEntity.accepted().body(replay);
                }));
    }

    private static Actor staff(Caller caller) {
        return new Actor(caller.has(UserRole.OPS) ? Actor.Type.OPS : Actor.Type.ADMIN, caller.userId().toString());
    }

    record ReplayBody(@NotBlank @Size(max = 249) String consumer, @NotNull Instant from, @NotNull Instant to, Boolean reprocess) {
    }
}
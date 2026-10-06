package com.ridehailing.audit.app;

import com.ridehailing.audit.AuditEntry;
import com.ridehailing.audit.AuditLog;
import com.ridehailing.audit.db.AuditLogRepository;
import com.ridehailing.platform.LogContext;
import com.ridehailing.shared.Ids;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuditLogService implements AuditLog {

    private final AuditLogRepository repository;
    private final Clock clock;

    AuditLogService(AuditLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        repository.insert(Ids.newId(), clock.instant(), entry, MDC.get(LogContext.REQUEST_ID),
                MDC.get(LogContext.CORRELATION_ID));
    }

    @Override
    public List<AuditRecord> entries(String entityType, Collection<String> entityIds) {
        return entityIds.isEmpty() ? List.of() : repository.entries(entityType, entityIds);
    }
}

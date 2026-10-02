package com.ridehailing.audit;

/** The append-only audit log (FR-A1, LLD §5.6). */
public interface AuditLog {

    /**
     * Records {@code entry} in the caller's transaction, with the request and correlation IDs of the logging context.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException if there is no transaction
     */
    void record(AuditEntry entry);
}

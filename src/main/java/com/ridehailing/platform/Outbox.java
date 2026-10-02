package com.ridehailing.platform;

/** Records domain events in the caller's transaction, for the relay to deliver after commit (ADR-008, LLD §5.2). */
public interface Outbox {

    /**
     * Appends {@code event} in the caller's transaction, so it exists if and only if the state change commits.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException if there is no transaction
     */
    void append(DomainEvent event);
}

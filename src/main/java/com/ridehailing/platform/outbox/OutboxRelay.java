package com.ridehailing.platform.outbox;

import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.InstanceId;
import com.ridehailing.platform.Leases;
import com.ridehailing.platform.Role;
import com.ridehailing.platform.RoleComponent;
import com.ridehailing.platform.leases.LeaseProperties;
import com.ridehailing.platform.workers.BackgroundWorker;
import com.ridehailing.platform.workers.WorkerProperties;
import java.util.List;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers committed events to in-process consumers in ID order, under the cluster-wide lease {@code outbox-relay}
 * (LLD §5.2). A batch is marked published only while the lease is still ours, so a stale relay can't mark rows the new
 * holder is delivering; consumers' inboxes make a repeated delivery harmless.
 */
@RoleComponent(Role.WORKER)
class OutboxRelay extends BackgroundWorker {

    static final String LEASE = "outbox-relay";

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRows rows;
    private final EventConsumers consumers;
    private final ConsumerDelivery delivery;
    private final Leases leases;
    private final String holder;
    private final OutboxProperties properties;
    private final LeaseProperties leaseProperties;
    private long token;
    private long renewedAt;

    OutboxRelay(OutboxRows rows, EventConsumers consumers, ConsumerDelivery delivery, Leases leases, InstanceId instance,
            OutboxProperties properties, LeaseProperties leaseProperties, WorkerProperties workers) {
        super("outbox-relay", Role.WORKER, 1, properties.idlePoll(), workers);
        this.rows = rows;
        this.consumers = consumers;
        this.delivery = delivery;
        this.leases = leases;
        this.holder = instance.value();
        this.properties = properties;
        this.leaseProperties = leaseProperties;
    }

    @Override
    protected boolean work() {
        return relayBatch();
    }

    /** Delivers one batch if this process holds the lease; returns whether the batch was full. */
    boolean relayBatch() {
        if (!holdLease()) {
            return false;
        }
        List<OutboxRows.Row> batch = rows.unpublished(properties.batchSize());
        if (batch.isEmpty()) {
            return false;
        }
        for (OutboxRows.Row row : batch) {
            if (!holdLease()) {
                return false;
            }
            for (EventConsumer consumer : consumers.subscribedTo(row.envelope().eventType())) {
                delivery.deliverWithRetries(consumer, row.envelope(), row.id());
            }
        }
        int marked = rows.markPublished(batch.stream().map(OutboxRows.Row::id).toList(), LEASE, holder, token);
        if (marked < batch.size()) {
            log.warn("Lost the {} lease before marking {} events published; the new holder delivers them again",
                    LEASE, batch.size() - marked);
            token = 0;
            return false;
        }
        return batch.size() == properties.batchSize();
    }

    /** Renews the lease when due, or tries to take it; false if another process holds it. */
    private boolean holdLease() {
        long now = System.nanoTime();
        if (token != 0) {
            if (now - renewedAt < leaseProperties.renewEvery().toNanos()) {
                return true;
            }
            if (leases.renew(LEASE, holder, token, leaseProperties.ttl())) {
                renewedAt = now;
                return true;
            }
            log.warn("Lost the {} lease (token {})", LEASE, token);
            token = 0;
        }
        OptionalLong acquired = leases.acquire(LEASE, holder, leaseProperties.ttl());
        if (acquired.isEmpty()) {
            return false;
        }
        token = acquired.getAsLong();
        renewedAt = now;
        log.info("Holding the {} lease (token {})", LEASE, token);
        return true;
    }

    @Override
    protected void stopped() {
        if (token != 0) {
            leases.release(LEASE, holder, token);
            token = 0;
        }
    }
}

package com.ridehailing.platform.leases;

import com.ridehailing.platform.Leases;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Leases on {@code platform.leases}; expiry is judged by the database clock only (LLD §1.4, §5.5). */
@Component
class JdbcLeases implements Leases {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    JdbcLeases(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public OptionalLong acquire(String name, String holder, Duration ttl) {
        return jdbc.sql("""
                        INSERT INTO platform.leases AS lease (name, holder, token, expires_at)
                        VALUES (:name, :holder, 1, now() + make_interval(secs => :ttl))
                        ON CONFLICT (name) DO UPDATE
                        SET holder = :holder, token = lease.token + 1, expires_at = now() + make_interval(secs => :ttl)
                        WHERE lease.holder IS NULL OR lease.expires_at < now()
                        RETURNING token
                        """)
                .param("name", name)
                .param("holder", holder)
                .param("ttl", seconds(ttl))
                .query(Long.class)
                .optional()
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
    }

    @Override
    public boolean renew(String name, String holder, long token, Duration ttl) {
        return jdbc.sql("""
                        UPDATE platform.leases SET expires_at = now() + make_interval(secs => :ttl)
                        WHERE name = :name AND holder = :holder AND token = :token AND expires_at >= now()
                        """)
                .param("ttl", seconds(ttl))
                .param("name", name)
                .param("holder", holder)
                .param("token", token)
                .update() == 1;
    }

    @Override
    public void release(String name, String holder, long token) {
        jdbc.sql("""
                        UPDATE platform.leases SET holder = NULL, expires_at = NULL
                        WHERE name = :name AND holder = :holder AND token = :token
                        """)
                .param("name", name)
                .param("holder", holder)
                .param("token", token)
                .update();
    }

    @Override
    public boolean runIfHeld(String name, String holder, long token, Runnable work) {
        Objects.requireNonNull(work, "work");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Lease-guarded work must own its transaction");
        }
        return Boolean.TRUE.equals(transactions.execute(transaction -> {
            boolean exists = jdbc.sql("SELECT name FROM platform.leases WHERE name = :name FOR UPDATE")
                    .param("name", name).query(String.class).optional().isPresent();
            if (!exists || !held(name, holder, token)) {
                return false;
            }
            work.run();
            if (!held(name, holder, token)) {
                transaction.setRollbackOnly();
                return false;
            }
            return true;
        }));
    }

    private boolean held(String name, String holder, long token) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM platform.leases
                    WHERE name = :name AND holder = :holder AND token = :token AND expires_at > clock_timestamp())
                """).param("name", name).param("holder", holder).param("token", token).query(Boolean.class).single();
    }

    private static double seconds(Duration duration) {
        return duration.toNanos() / 1e9;
    }
}

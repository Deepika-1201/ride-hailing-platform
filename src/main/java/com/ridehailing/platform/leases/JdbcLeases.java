package com.ridehailing.platform.leases;

import com.ridehailing.platform.Leases;
import java.time.Duration;
import java.util.OptionalLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Leases on {@code platform.leases}; expiry is judged by the database clock only (LLD §1.4, §5.5). */
@Component
class JdbcLeases implements Leases {

    private final JdbcClient jdbc;

    JdbcLeases(JdbcClient jdbc) {
        this.jdbc = jdbc;
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

    private static double seconds(Duration duration) {
        return duration.toNanos() / 1e9;
    }
}

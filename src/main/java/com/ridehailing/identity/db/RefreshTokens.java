package com.ridehailing.identity.db;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Refresh tokens, stored as SHA-256 hashes, in families that start at sign-in (LLD §12.2). */
@Repository
public class RefreshTokens {

    private final JdbcClient jdbc;

    RefreshTokens(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID familyId, UUID parentId, UUID userId, byte[] tokenHash, Duration ttl) {
        jdbc.sql("""
                        INSERT INTO identity.refresh_tokens (id, family_id, parent_id, user_id, token_hash, issued_at,
                                                             expires_at)
                        VALUES (:id, :familyId, :parentId, :userId, :tokenHash, now(), now() + make_interval(secs => :ttl))
                        """)
                .param("id", id)
                .param("familyId", familyId)
                .param("parentId", parentId)
                .param("userId", userId)
                .param("tokenHash", tokenHash)
                .param("ttl", (double) ttl.toSeconds())
                .update();
    }

    /** The token with this hash, locked, with its state judged by the database clock. */
    public Optional<StoredToken> findForUpdate(byte[] tokenHash, Duration reuseGrace) {
        return jdbc.sql("""
                        SELECT id, family_id, user_id,
                               revoked_at IS NOT NULL OR expires_at <= now() AS dead,
                               rotated_at IS NOT NULL AS rotated,
                               coalesce(rotated_at + make_interval(secs => :grace) >= now(), false) AS in_grace
                        FROM identity.refresh_tokens WHERE token_hash = :tokenHash
                        FOR UPDATE
                        """)
                .param("grace", reuseGrace.toNanos() / 1e9)
                .param("tokenHash", tokenHash)
                .query((row, rowNumber) -> new StoredToken(row.getObject("id", UUID.class),
                        row.getObject("family_id", UUID.class), row.getObject("user_id", UUID.class),
                        row.getBoolean("dead"), row.getBoolean("rotated"), row.getBoolean("in_grace")))
                .optional();
    }

    public Optional<UUID> familyOf(byte[] tokenHash) {
        return jdbc.sql("SELECT family_id FROM identity.refresh_tokens WHERE token_hash = :tokenHash")
                .param("tokenHash", tokenHash)
                .query(UUID.class)
                .optional();
    }

    public void markRotated(UUID id) {
        jdbc.sql("UPDATE identity.refresh_tokens SET rotated_at = now() WHERE id = :id AND rotated_at IS NULL")
                .param("id", id)
                .update();
    }

    public void revokeFamily(UUID familyId) {
        jdbc.sql("UPDATE identity.refresh_tokens SET revoked_at = now() WHERE family_id = :familyId AND revoked_at IS NULL")
                .param("familyId", familyId)
                .update();
    }

    /** Revokes every token issued after this one in its family: its children, their children, and so on. */
    public void revokeDescendants(UUID id) {
        jdbc.sql("""
                        WITH RECURSIVE descendants AS (
                            SELECT id FROM identity.refresh_tokens WHERE parent_id = :id
                            UNION ALL
                            SELECT child.id FROM identity.refresh_tokens child
                            JOIN descendants d ON child.parent_id = d.id)
                        UPDATE identity.refresh_tokens SET revoked_at = now()
                        WHERE id IN (SELECT id FROM descendants) AND revoked_at IS NULL
                        """)
                .param("id", id)
                .update();
    }

    /** Deletes up to {@code batch} tokens that expired before {@code age} ago; returns how many. */
    public int deleteExpiredBefore(Duration age, int batch) {
        return jdbc.sql("""
                        DELETE FROM identity.refresh_tokens WHERE id IN (
                            SELECT id FROM identity.refresh_tokens
                            WHERE expires_at < now() - make_interval(secs => :age) LIMIT :batch)
                        """)
                .param("age", (double) age.toSeconds())
                .param("batch", batch)
                .update();
    }

    /** {@code dead}: revoked or expired. {@code inGrace}: rotated within the reuse grace period. */
    public record StoredToken(UUID id, UUID familyId, UUID userId, boolean dead, boolean rotated, boolean inGrace) {
    }
}

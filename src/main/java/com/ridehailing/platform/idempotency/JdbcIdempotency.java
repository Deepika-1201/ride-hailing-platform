package com.ridehailing.platform.idempotency;

import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.IdempotentCall;
import com.ridehailing.platform.Transactions;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The key and the command share one transaction (LLD §5.1): the command's effects and the stored response commit
 * together, a second call with the key waits on the key's row lock, and a failed command releases the key.
 */
@Component
class JdbcIdempotency implements Idempotency {

    private static final Pattern VALID_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final JdbcClient jdbc;
    private final Transactions transactions;
    private final JsonMapper json;
    private final IdempotencyProperties properties;

    JdbcIdempotency(JdbcClient jdbc, Transactions transactions, JsonMapper json, IdempotencyProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.properties = properties;
    }

    @Override
    public ResponseEntity<?> execute(IdempotentCall call, Supplier<? extends ResponseEntity<?>> command) {
        Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
                "An idempotent command starts its own transaction");
        Assert.hasText(call.principal(), "principal must not be empty");
        Assert.hasText(call.operation(), "operation must not be empty");
        validate(call.key());
        byte[] hash = requestHash(call);
        return transactions.<ResponseEntity<?>>execute(() -> {
            jdbc.sql("""
                            DELETE FROM platform.idempotency_keys
                            WHERE principal = :principal AND key = :key AND expires_at <= now()
                            """)
                    .param("principal", call.principal())
                    .param("key", call.key())
                    .update();
            if (!claim(call, hash)) {
                return replay(call, hash);
            }
            ResponseEntity<?> response = command.get();
            store(call, response);
            return response;
        });
    }

    private static void validate(String key) {
        if (key == null || key.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "This command needs an " + HEADER + " header.");
        }
        if (!VALID_KEY.matcher(key).matches()) {
            throw ApiException.invalid(HEADER, "must be 1 to 255 visible ASCII characters");
        }
    }

    /** True if this call now holds the key; false if an earlier call finished with it. */
    private boolean claim(IdempotentCall call, byte[] hash) {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", properties.lockTimeout().toMillis() + "ms")
                .query(String.class)
                .single();
        int inserted;
        try {
            inserted = jdbc.sql("""
                            INSERT INTO platform.idempotency_keys (principal, key, request_hash, response_status, expires_at)
                            VALUES (:principal, :key, :hash, 0, now() + make_interval(secs => :ttl))
                            ON CONFLICT (principal, key) DO NOTHING
                            """)
                    .param("principal", call.principal())
                    .param("key", call.key())
                    .param("hash", hash)
                    .param("ttl", (double) properties.ttl().toSeconds())
                    .update();
        } catch (DataAccessException e) {
            // Spring doesn't translate lock_not_available to a specific exception.
            if (e.getMostSpecificCause() instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_IN_PROGRESS",
                        "A request with this " + HEADER + " is still running.", Duration.ofSeconds(1), Map.of());
            }
            throw e;
        }
        jdbc.sql("SET LOCAL lock_timeout TO DEFAULT").update();
        return inserted == 1;
    }

    private ResponseEntity<?> replay(IdempotentCall call, byte[] hash) {
        StoredResponse stored = jdbc.sql("""
                        SELECT request_hash, response_status, response_content_type, response_location, response_body
                        FROM platform.idempotency_keys WHERE principal = :principal AND key = :key
                        """)
                .param("principal", call.principal())
                .param("key", call.key())
                .query(JdbcIdempotency::storedResponse)
                .single();
        if (!MessageDigest.isEqual(stored.hash(), hash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED",
                    "This " + HEADER + " was already used for a different request.");
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(stored.status()).header(REPLAYED_HEADER, "true");
        if (stored.location() != null) {
            response.location(URI.create(stored.location()));
        }
        if (stored.contentType() != null) {
            response.contentType(MediaType.parseMediaType(stored.contentType()));
        }
        return stored.body() == null ? response.build() : response.body(json.readTree(stored.body()));
    }

    private void store(IdempotentCall call, ResponseEntity<?> response) {
        URI location = response.getHeaders().getLocation();
        MediaType contentType = response.getHeaders().getContentType();
        jdbc.sql("""
                        UPDATE platform.idempotency_keys
                        SET response_status = :status, response_content_type = :contentType,
                            response_location = :location, response_body = :body
                        WHERE principal = :principal AND key = :key
                        """)
                .param("status", response.getStatusCode().value())
                .param("contentType", contentType == null ? null : contentType.toString())
                .param("location", location == null ? null : location.toString())
                .param("body", response.getBody() == null ? null : json.writeValueAsString(response.getBody()))
                .param("principal", call.principal())
                .param("key", call.key())
                .update();
    }

    /** SHA-256 of the operation and the body as JSON with sorted map keys, so formatting doesn't matter. */
    private byte[] requestHash(IdempotentCall call) {
        String body = call.body() == null
                ? ""
                : json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(call.body());
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((call.operation() + "\n" + body).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static StoredResponse storedResponse(ResultSet row, int rowNumber) throws SQLException {
        return new StoredResponse(row.getBytes("request_hash"), row.getInt("response_status"),
                row.getString("response_content_type"), row.getString("response_location"),
                row.getString("response_body"));
    }

    private record StoredResponse(byte[] hash, int status, String contentType, String location, String body) {
    }
}

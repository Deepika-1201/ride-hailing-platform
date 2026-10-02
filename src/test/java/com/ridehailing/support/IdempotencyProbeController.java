package com.ridehailing.support;

import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.IdempotentCall;
import com.ridehailing.platform.PublicEndpoint;
import com.ridehailing.shared.Ids;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/** A command behind {@link Idempotency}, for testing the HTTP side: headers and problem codes. */
@TestComponent
@ApiController
@PublicEndpoint
public class IdempotencyProbeController {

    public static final String PATH = "/test/idempotent";
    public static final String EFFECT = "idempotent-command";

    private final Idempotency idempotency;
    private final JdbcClient jdbc;

    IdempotencyProbeController(Idempotency idempotency, JdbcClient jdbc) {
        this.idempotency = idempotency;
        this.jdbc = jdbc;
    }

    @PostMapping(PATH)
    ResponseEntity<?> create(@RequestHeader(value = Idempotency.HEADER, required = false) String key,
            @RequestBody Map<String, Object> body) {
        return idempotency.execute(new IdempotentCall("tester", key, "POST " + PATH, body), () -> {
            UUID id = Ids.newId();
            Effects.record(jdbc, EFFECT, key);
            return ResponseEntity.created(URI.create(PATH + "/" + id)).body(Map.of("id", id, "amount", body.get("amount")));
        });
    }
}

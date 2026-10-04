package com.ridehailing.payment.app;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.payment.db.ChargeRepository.ChargeRow;
import com.ridehailing.platform.Cursor;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.TestUsers;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Operations' list of charges (LLD §11.10): by status, newest first, a page at a time, with attempts. */
class OpsPaymentsTests extends PaymentTest {

    private static final String PAYMENTS = "/v1/ops/payments";

    @Autowired
    private TestUsers users;

    @Test
    void chargesComeNewestFirstAPageAtATimeWithTheirAttempts() {
        String ops = users.create(UserRole.OPS).authorization();
        UUID rider = UUID.randomUUID();
        List<ChargeRow> failed = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            failed.add(pendingCharge(rider, null, "NO_SHOW_FEE", "tok_decline"));
            send();
        }

        List<String> listed = new ArrayList<>();
        String cursor = "";
        do {
            JsonNode page = assertAnswered("GET", PAYMENTS,
                    getAs(ops, PAYMENTS + "?status=FAILED&limit=1" + cursor), 200);
            page.get("items").forEach(item -> {
                if (item.get("rider_id").asString().equals(rider.toString())) {
                    listed.add(item.get("id").asString());
                    assertThat(item.get("attempts")).singleElement().satisfies(attempt -> {
                        assertThat(attempt.get("status").asString()).isEqualTo("FAILED");
                        assertThat(attempt.get("failure_code").asString()).isEqualTo("DECLINED");
                    });
                    assertThat(item.get("refunded").get("amount_paise").asLong()).isZero();
                }
            });
            cursor = page.has("next_cursor") ? "&cursor=" + page.get("next_cursor").asString() : null;
        } while (cursor != null && listed.size() < 3);

        assertThat(listed).containsExactly(failed.get(2).id().toString(), failed.get(1).id().toString(),
                failed.get(0).id().toString());

        List<Cursor> oldest = jdbc.sql("""
                        SELECT created_at, id FROM payment.charges WHERE status = 'FAILED'
                        ORDER BY created_at, id LIMIT 2
                        """)
                .query((row, n) -> new Cursor(row.getObject("created_at", OffsetDateTime.class).toInstant(),
                        row.getObject("id", UUID.class)))
                .list();
        JsonNode last = assertAnswered("GET", PAYMENTS, getAs(ops, PAYMENTS + "?status=FAILED&limit=1&cursor="
                + oldest.get(1).encode()), 200);
        assertThat(last.get("items")).singleElement().satisfies(item ->
                assertThat(item.get("id").asString()).isEqualTo(oldest.get(0).id().toString()));
        assertThat(last.has("next_cursor")).as("the last page, though full").isFalse();
    }

    @Test
    void aBadFilterIsRefusedAndOnlyOperationsSeeTheList() {
        String ops = users.create(UserRole.OPS).authorization();

        for (String query : List.of("?status=PAID", "?older_than=24h", "?older_than=-PT1H", "?limit=0",
                "?cursor=nope")) {
            assertProblem("GET", PAYMENTS, getAs(ops, PAYMENTS + query), 400, "VALIDATION_FAILED");
        }
        assertProblem("GET", PAYMENTS, getAs(users.create(UserRole.DRIVER).authorization(), PAYMENTS), 403,
                "FORBIDDEN");
        assertAnswered("GET", PAYMENTS, getAs(users.create(UserRole.ADMIN).authorization(), PAYMENTS + "?limit=1"),
                200);
    }
}

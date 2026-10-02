package com.ridehailing.platform.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.support.IntegrationTest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** LLD §4.9: seeds load after the schema migrations, with a history of their own, as the local profile runs them. */
@TestPropertySource(properties = "ride.seed.enabled=true")
class SeedDataTests extends IntegrationTest {

    private static final String SEEDED_ADMIN = "+919000000002";

    @Autowired
    private JdbcClient jdbc;

    @Test
    void theStaffAccountsAreSeeded() {
        List<String> staff = jdbc.sql("""
                        SELECT phone || ':' || array_to_string(roles, ',') FROM identity.users
                        WHERE phone IN ('+919000000001', '+919000000002') ORDER BY phone
                        """)
                .query(String.class)
                .list();

        assertThat(staff).containsExactly("+919000000001:OPS", "+919000000002:ADMIN");
    }

    @Test
    void seedsKeepAHistoryApartFromTheSchemas() {
        assertThat(jdbc.sql("SELECT count(*) FROM identity.flyway_seed_history WHERE version = '1' AND success")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM identity.flyway_schema_history WHERE description = 'staff'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void theSeededAdminSignsInWithTheFixedCodeAndReachesAdminEndpoints() {
        postJson("/v1/auth/otp", Map.of(), "{\"phone\": \"" + SEEDED_ADMIN + "\"}");
        HttpResponse<String> signedIn = postJson("/v1/auth/token", Map.of(),
                "{\"phone\": \"" + SEEDED_ADMIN + "\", \"code\": \"123456\"}");

        JsonNode tokens = json(signedIn);
        assertThat(tokens.get("user").get("roles").valueStream().map(JsonNode::asString)).containsExactly("ADMIN");
        assertThat(getAs("Bearer " + tokens.get("access_token").asString(), "/test/access/admin").statusCode())
                .isEqualTo(200);
    }
}

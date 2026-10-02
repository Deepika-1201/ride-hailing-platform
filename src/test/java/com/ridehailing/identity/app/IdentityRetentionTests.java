package com.ridehailing.identity.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** LLD §5.7: one-time codes go after a day, refresh tokens a day after they expire. */
class IdentityRetentionTests extends IntegrationTest {

    @Autowired
    private IdentityRetention retention;

    @Autowired
    private TestUsers users;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void deletesOnlyCodesAndTokensPastTheirRetention() {
        String phone = TestUsers.randomPhone();
        UUID oldCode = challenge(phone, "2 days");
        UUID recentCode = challenge(phone, "2 hours");
        TestUser user = users.create(UserRole.RIDER);
        UUID longExpired = refreshToken(user, "-2 days");
        UUID justExpired = refreshToken(user, "-2 hours");
        UUID live = refreshToken(user, "29 days");

        retention.purge();

        assertThat(ids("SELECT id FROM identity.otp_challenges WHERE phone = :owner", phone))
                .contains(recentCode).doesNotContain(oldCode);
        assertThat(ids("SELECT id FROM identity.refresh_tokens WHERE user_id::text = :owner", user.id().toString()))
                .containsExactlyInAnyOrder(justExpired, live).doesNotContain(longExpired);
    }

    private UUID challenge(String phone, String age) {
        UUID id = Ids.newId();
        jdbc.sql("""
                        INSERT INTO identity.otp_challenges (id, phone, code_hmac, expires_at, created_at)
                        VALUES (:id, :phone, '\\x00', now(), now() - CAST(:age AS interval))
                        """)
                .param("id", id)
                .param("phone", phone)
                .param("age", age)
                .update();
        return id;
    }

    private UUID refreshToken(TestUser user, String expiresIn) {
        UUID id = Ids.newId();
        jdbc.sql("""
                        INSERT INTO identity.refresh_tokens (id, family_id, user_id, token_hash, issued_at, expires_at)
                        VALUES (:id, :id, :userId, sha256(convert_to(CAST(:id AS text), 'UTF8')), now() - interval '30 days',
                                now() + CAST(:expiresIn AS interval))
                        """)
                .param("id", id)
                .param("userId", user.id())
                .param("expiresIn", expiresIn)
                .update();
        return id;
    }

    private List<UUID> ids(String sql, String owner) {
        return jdbc.sql(sql).param("owner", owner).query(UUID.class).list();
    }
}

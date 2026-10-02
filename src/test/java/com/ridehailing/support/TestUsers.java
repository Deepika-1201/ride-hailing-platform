package com.ridehailing.support;

import com.ridehailing.platform.AccessTokens;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Users created straight in the database, with real access tokens, for tests about access rather than sign-in. */
@TestComponent
public class TestUsers {

    private final JdbcClient jdbc;
    private final AccessTokens accessTokens;

    TestUsers(JdbcClient jdbc, AccessTokens accessTokens) {
        this.jdbc = jdbc;
        this.accessTokens = accessTokens;
    }

    public TestUser create(UserRole... roles) {
        UUID id = Ids.newId();
        String phone = randomPhone();
        jdbc.sql("INSERT INTO identity.users (id, phone, roles) VALUES (:id, :phone, string_to_array(:roles, ','))")
                .param("id", id)
                .param("phone", phone)
                .param("roles", Arrays.stream(roles).map(UserRole::name).collect(Collectors.joining(",")))
                .update();
        return new TestUser(id, phone, Set.of(roles), "Bearer " + accessTokens.issue(id, Set.of(roles)).value());
    }

    /** An Indian mobile number unlikely to repeat across tests sharing the database. */
    public static String randomPhone() {
        return "+9198" + "%08d".formatted(ThreadLocalRandom.current().nextInt(100_000_000));
    }

    public record TestUser(UUID id, String phone, Set<UserRole> roles, String authorization) {
    }
}

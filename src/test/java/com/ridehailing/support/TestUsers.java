package com.ridehailing.support;

import com.ridehailing.platform.AccessTokens;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Users created straight in the database, with real access tokens, for tests about access rather than sign-in. */
@TestComponent
public class TestUsers {

    private static final AtomicInteger PHONES = new AtomicInteger();

    private final JdbcClient jdbc;
    private final AccessTokens accessTokens;

    TestUsers(JdbcClient jdbc, AccessTokens accessTokens) {
        this.jdbc = jdbc;
        this.accessTokens = accessTokens;
    }

    public TestUser create(UserRole... roles) {
        UUID id = Ids.newId();
        String phone = newPhone();
        jdbc.sql("INSERT INTO identity.users (id, phone, roles) VALUES (:id, :phone, string_to_array(:roles, ','))")
                .param("id", id)
                .param("phone", phone)
                .param("roles", Arrays.stream(roles).map(UserRole::name).collect(Collectors.joining(",")))
                .update();
        return new TestUser(id, phone, Set.of(roles), "Bearer " + accessTokens.issue(id, Set.of(roles)).value());
    }

    /**
     * A mobile number no other test in this JVM has used. Counted rather than random: the races create thousands of
     * users, and random numbers then repeat. The database lives as long as the JVM, and so does the count.
     */
    public static String newPhone() {
        return "+9198" + "%08d".formatted(PHONES.incrementAndGet());
    }

    public record TestUser(UUID id, String phone, Set<UserRole> roles, String authorization) {
    }
}

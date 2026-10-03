package com.ridehailing.identity.db;

import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

@Repository
public class Users {

    private static final String COLUMNS = "SELECT id, roles, status = 'ACTIVE' AS active FROM identity.users ";

    private final JdbcClient jdbc;

    Users(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> find(UUID id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id").param("id", id).query(Users::user).optional();
    }

    /** The phone's user, created as a rider on a first sign-in. */
    public User findOrCreateRider(String phone) {
        jdbc.sql("""
                        INSERT INTO identity.users (id, phone, roles) VALUES (:id, :phone, ARRAY['RIDER'])
                        ON CONFLICT (phone) DO NOTHING
                        """)
                .param("id", Ids.newId())
                .param("phone", phone)
                .update();
        return jdbc.sql(COLUMNS + "WHERE phone = :phone").param("phone", phone).query(Users::user).single();
    }

    /** Creates the user with the roles, or adds them to the phone's user; returns the ID. */
    public UUID ensure(String phone, Set<UserRole> roles) {
        return jdbc.sql("""
                        INSERT INTO identity.users (id, phone, roles) VALUES (:id, :phone, :roles)
                        ON CONFLICT (phone) DO UPDATE
                        SET roles = ARRAY(SELECT DISTINCT r FROM unnest(identity.users.roles || EXCLUDED.roles) AS r
                                          ORDER BY r),
                            version = identity.users.version + 1
                        RETURNING id
                        """)
                .param("id", Ids.newId())
                .param("phone", phone)
                .param("roles", new SqlArrayValue("text", roles.stream().map(Enum::name).sorted().toArray()))
                .query(UUID.class)
                .single();
    }

    private static User user(ResultSet row, int rowNumber) throws SQLException {
        String[] roles = (String[]) row.getArray("roles").getArray();
        return new User(row.getObject("id", UUID.class),
                Arrays.stream(roles).map(UserRole::valueOf).collect(Collectors.toUnmodifiableSet()),
                row.getBoolean("active"));
    }

    public record User(UUID id, Set<UserRole> roles, boolean active) {
    }
}

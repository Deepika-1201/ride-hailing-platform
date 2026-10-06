package com.ridehailing.support;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** The project's PostgreSQL + PostGIS image ({@code docker/postgres}), built on first use and cached by Docker. */
public final class Postgis {

    private static final String IMAGE = "ride-hailing/postgres-postgis:18";

    private static PostgreSQLContainer shared;

    private Postgis() {
    }

    public static PostgreSQLContainer newContainer() {
        String image = new ImageFromDockerfile(IMAGE, false)
                .withDockerfile(Path.of("docker/postgres/Dockerfile"))
                .get();
        return new PostgreSQLContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("ridehailing")
                .withUsername("ridehailing")
                .withPassword("ridehailing");
    }

    /** One database per test JVM, shared by every Spring context; Testcontainers removes it when the JVM exits. */
    public static synchronized PostgreSQLContainer shared() {
        if (shared == null) {
            shared = newContainer();
            shared.start();
        }
        return shared;
    }

    /** A session of its own, outside the application's pool, for holding locks the application must meet. */
    public static Connection connection() throws SQLException {
        PostgreSQLContainer database = shared();
        return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    /** The JDBC URL of another database in the shared container, created on first use. */
    public static synchronized String database(String name) {
        PostgreSQLContainer container = shared();
        try (Connection connection = connection();
                PreparedStatement exists = connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            exists.setString(1, name);
            try (ResultSet found = exists.executeQuery(); Statement create = connection.createStatement()) {
                if (!found.next()) {
                    create.execute("CREATE DATABASE " + name);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Couldn't create the database " + name, e);
        }
        return "jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + name;
    }
}

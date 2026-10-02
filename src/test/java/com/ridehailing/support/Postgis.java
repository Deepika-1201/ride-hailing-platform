package com.ridehailing.support;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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
}

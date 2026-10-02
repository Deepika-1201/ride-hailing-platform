package com.ridehailing.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.support.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** A process running one role other than {@code api}: healthy, but no public API on port 8080. */
abstract class BackgroundRoleTests extends IntegrationTest {

    abstract String role();

    @Test
    void infoReportsOnlyItsRole() {
        assertThat(reportedRoles()).containsExactly(role());
    }

    @Test
    void doesNotServeThePublicApi() {
        HttpResponse<String> response = get(port, API_PROBE);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    void isReadyForTraffic() {
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }
}

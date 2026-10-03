package com.ridehailing.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"ride.roles=api", "ride.location.single-process-check=false"})
class ApiRoleTests extends IntegrationTest {

    @Test
    void infoReportsOnlyTheApiRole() {
        assertThat(reportedRoles()).containsExactly("api");
    }

    @Test
    void servesThePublicApi() {
        assertThat(get(port, API_PROBE).statusCode()).isEqualTo(200);
    }

    @Test
    void runsNoBackgroundLoop() {
        assertThat(backgroundLoops()).isEmpty();
    }
}

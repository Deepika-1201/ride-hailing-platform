package com.ridehailing.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.support.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;

/** The default: one process runs all four roles, as locally (ADR-001). */
class AllRolesTests extends IntegrationTest {

    @Autowired
    private ApplicationAvailability availability;

    @Test
    void infoReportsAllFourRoles() {
        assertThat(reportedRoles()).containsExactly("api", "realtime", "dispatch", "worker");
    }

    @Test
    void livenessAndReadinessAreUpOnBothPorts() {
        assertThat(availability.getReadinessState())
                .as("last readiness change: %s", availability.getLastChangeEvent(ReadinessState.class))
                .isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
        for (String path : new String[] {"/actuator/health/liveness", "/actuator/health/readiness"}) {
            HttpResponse<String> probe = get(managementPort, path);
            assertThat(probe.statusCode()).as("%s: %s", path, probe.body()).isEqualTo(200);
            assertThat(json(probe).get("status").asString()).as(path).isEqualTo("UP");
        }
        assertThat(get(port, "/livez").statusCode()).isEqualTo(200);
        assertThat(get(port, "/readyz").statusCode()).isEqualTo(200);
    }

    @Test
    void servesThePublicApi() {
        assertThat(get(port, API_PROBE).statusCode()).isEqualTo(200);
    }

    @Test
    void runsEveryBackgroundLoop() {
        assertThat(backgroundLoops()).containsExactly("outboxRelay", "timerPoller");
    }

    @Test
    void exposesPrometheusMetricsOnTheManagementPortOnly() {
        HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");

        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("jvm_memory_used_bytes");
        assertThat(get(port, "/actuator/prometheus").statusCode()).isEqualTo(404);
    }
}

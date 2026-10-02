package com.ridehailing.platform;

import java.util.List;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "ride.roles=worker")
class WorkerRoleTests extends BackgroundRoleTests {

    @Override
    String role() {
        return "worker";
    }

    @Override
    List<String> loops() {
        return List.of("outboxRelay");
    }
}

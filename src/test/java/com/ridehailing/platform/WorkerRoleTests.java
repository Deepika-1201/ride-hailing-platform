package com.ridehailing.platform;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "ride.roles=worker")
class WorkerRoleTests extends BackgroundRoleTests {

    @Override
    String role() {
        return "worker";
    }
}

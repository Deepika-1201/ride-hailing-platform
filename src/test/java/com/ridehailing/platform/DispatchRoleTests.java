package com.ridehailing.platform;

import java.util.List;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"ride.roles=dispatch", "ride.location.single-process-check=false"})
class DispatchRoleTests extends BackgroundRoleTests {

    @Override
    String role() {
        return "dispatch";
    }

    @Override
    List<String> loops() {
        return List.of("timerPoller");
    }
}

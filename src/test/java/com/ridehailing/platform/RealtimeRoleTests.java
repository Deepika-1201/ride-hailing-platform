package com.ridehailing.platform;

import java.util.List;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"ride.roles=realtime", "ride.location.single-process-check=false"})
class RealtimeRoleTests extends BackgroundRoleTests {

    @Override
    String role() {
        return "realtime";
    }

    @Override
    List<String> loops() {
        return List.of();
    }
}

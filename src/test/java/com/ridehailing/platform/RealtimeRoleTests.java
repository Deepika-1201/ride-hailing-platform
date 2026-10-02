package com.ridehailing.platform;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "ride.roles=realtime")
class RealtimeRoleTests extends BackgroundRoleTests {

    @Override
    String role() {
        return "realtime";
    }
}

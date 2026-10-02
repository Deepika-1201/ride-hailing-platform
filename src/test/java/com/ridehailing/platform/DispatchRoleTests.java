package com.ridehailing.platform;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "ride.roles=dispatch")
class DispatchRoleTests extends BackgroundRoleTests {

    @Override
    String role() {
        return "dispatch";
    }
}

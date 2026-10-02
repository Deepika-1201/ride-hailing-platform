package com.ridehailing.architecture.accessfixture;

import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.PublicEndpoint;
import com.ridehailing.shared.UserRole;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/** Fixtures for the access-declaration rule; {@code @TestComponent} keeps them out of the application. */
public final class AccessFixtures {

    private AccessFixtures() {
    }

    @TestComponent
    @ApiController
    public static class UndeclaredController {

        @GetMapping("/fixture/undeclared")
        String undeclared() {
            return "anyone?";
        }
    }

    @TestComponent
    @ApiController
    @AllowedRoles(UserRole.RIDER)
    public static class DeclaredController {

        @GetMapping("/fixture/by-class")
        String byClass() {
            return "riders";
        }

        @PostMapping("/fixture/by-method")
        @PublicEndpoint
        String byMethod() {
            return "anyone";
        }
    }
}

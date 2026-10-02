package com.ridehailing.support;

import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.PublicEndpoint;
import com.ridehailing.shared.UserRole;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Sample endpoints for each way of declaring access (LLD §12.4), until the modules have real ones. */
public final class AccessProbes {

    private AccessProbes() {
    }

    @TestComponent
    @ApiController
    public static class ByMethod {

        /** A rider's own resource: another rider gets 404, as if it didn't exist. */
        @GetMapping("/test/access/rider/things/{ownerId}")
        @AllowedRoles(UserRole.RIDER)
        Map<String, Object> riderThing(@PathVariable UUID ownerId, Caller caller) {
            if (!caller.userId().equals(ownerId)) {
                throw ApiException.notFound();
            }
            return Map.of("owner_id", ownerId);
        }

        @GetMapping("/test/access/staff")
        @AllowedRoles({UserRole.OPS, UserRole.ADMIN})
        Map<String, Object> staff(Caller caller) {
            return Map.of("user_id", caller.userId());
        }

        @GetMapping("/test/access/admin")
        @AllowedRoles(UserRole.ADMIN)
        Map<String, Object> admin() {
            return Map.of("admin", true);
        }

        @GetMapping("/test/access/public")
        @PublicEndpoint
        Map<String, Object> open() {
            return Map.of("public", true);
        }

        /** Declares nothing, which only test code can do: the ArchUnit rule forbids it in the application. */
        @GetMapping("/test/access/undeclared")
        Map<String, Object> undeclared() {
            return Map.of("reached", true);
        }
    }

    @TestComponent
    @ApiController
    @AllowedRoles(UserRole.DRIVER)
    public static class DriverByClass {

        @GetMapping("/test/access/driver")
        Map<String, Object> driver(Caller caller) {
            return Map.of("user_id", caller.userId());
        }

        @GetMapping("/test/access/driver/help")
        @PublicEndpoint
        Map<String, Object> help() {
            return Map.of("public", true);
        }
    }
}

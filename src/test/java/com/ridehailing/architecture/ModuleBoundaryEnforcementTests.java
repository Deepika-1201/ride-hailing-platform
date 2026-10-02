package com.ridehailing.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.architecture.fixture.FixtureApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

/** Proves the boundary check can fail: the fixture's module {@code alpha} uses an internal type of {@code beta}. */
class ModuleBoundaryEnforcementTests {

    @Test
    void reportsAModuleThatUsesAnotherModulesInternalType() {
        Violations violations = ApplicationModules.of(FixtureApplication.class, location -> true).detectViolations();

        assertThat(violations.hasViolations()).isTrue();
        assertThat(violations.getMessages())
                .anySatisfy(message -> assertThat(message).contains("alpha").contains("BetaInternals"));
    }
}

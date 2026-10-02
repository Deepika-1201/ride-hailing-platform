package com.ridehailing.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.RideHailingApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularityTests {

    private static final ApplicationModules MODULES = ApplicationModules.of(RideHailingApplication.class);

    @Test
    void modulesRespectTheirAllowedDependencies() {
        MODULES.verify();
    }

    @Test
    void everyModuleOfTheDesignExists() {
        assertThat(MODULES.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder(
                        "shared", "platform", "audit", "notification", "identity", "rider", "driver", "geography",
                        "rating", "location", "pricing", "payment", "ride", "dispatch", "operations");
    }

    @Test
    void writesModuleDiagrams() {
        new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}

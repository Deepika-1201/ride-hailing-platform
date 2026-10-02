package com.ridehailing.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.architecture.accessfixture.AccessFixtures;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/** The access-declaration rule catches a handler that declares nothing, so a forgotten endpoint fails the build. */
class AccessDeclarationRuleTests {

    @Test
    void flagsAHandlerThatDeclaresNoAccess() {
        var classes = new ClassFileImporter().importClasses(AccessFixtures.UndeclaredController.class);

        assertThatThrownBy(() -> ArchitectureTests.apiHandlersDeclareWhoMayCallThem.check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("undeclared() declares no access");
    }

    @Test
    void acceptsADeclarationOnTheClassOrTheMethod() {
        var classes = new ClassFileImporter().importClasses(AccessFixtures.DeclaredController.class);

        assertThatCode(() -> ArchitectureTests.apiHandlersDeclareWhoMayCallThem.check(classes)).doesNotThrowAnyException();
    }
}

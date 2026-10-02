package com.ridehailing.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/** Coding rules (ADR-019). Module boundaries are Spring Modulith's job ({@link ModularityTests}). */
@AnalyzeClasses(packages = "com.ridehailing", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

    private static final String[] TRANSACTION_OWNERS = {"..app..", "com.ridehailing.platform.."};

    @ArchTest
    static final ArchRule noFieldInjection = GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule noStandardStreams = GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule noJavaUtilLogging = GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule noGenericExceptions = GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

    @ArchTest
    static final ArchRule noJpa = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.hibernate..")
            .because("every state change is a visible SQL statement (ADR-002)");

    @ArchTest
    static final ArchRule publicControllersUseApiController = noClasses()
            .that().resideOutsideOfPackage("com.ridehailing.platform..")
            .should().beAnnotatedWith(RestController.class)
            .orShould().beAnnotatedWith(Controller.class)
            .because("@ApiController serves the public API only where the api role runs (LLD §1.3)");

    @ArchTest
    static final ArchRule noSpringScheduling = noMethods()
            .should().beAnnotatedWith(Scheduled.class)
            .because("background work runs in role-aware platform jobs (LLD §1.3, §5.7)");

    @ArchTest
    static final ArchRule transactionalClassesAreApplicationServices = noClasses()
            .that().resideOutsideOfPackages(TRANSACTION_OWNERS)
            .should().beAnnotatedWith(Transactional.class)
            .because("transactions start in application services and nowhere else (LLD §1.2)");

    @ArchTest
    static final ArchRule transactionalMethodsAreInApplicationServices = noMethods()
            .that().areDeclaredInClassesThat().resideOutsideOfPackages(TRANSACTION_OWNERS)
            .should().beAnnotatedWith(Transactional.class)
            .because("transactions start in application services and nowhere else (LLD §1.2)");
}

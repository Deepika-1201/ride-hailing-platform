package com.ridehailing.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.PublicEndpoint;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.util.stream.Stream;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @ArchTest
    static final ArchRule apiHandlersDeclareWhoMayCallThem = methods()
            .that().areDeclaredInClassesThat().areMetaAnnotatedWith(ApiController.class)
            .and().areMetaAnnotatedWith(RequestMapping.class)
            .should(declareAccess())
            .because("an endpoint is closed unless it declares who may call it (LLD §12.4)");

    private static ArchCondition<JavaMethod> declareAccess() {
        return new ArchCondition<>("declare @AllowedRoles or @PublicEndpoint on the method or its class") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean declared = Stream.of(AllowedRoles.class, PublicEndpoint.class).anyMatch(annotation ->
                        method.isAnnotatedWith(annotation) || method.getOwner().isAnnotatedWith(annotation));
                if (!declared) {
                    events.add(SimpleConditionEvent.violated(method, method.getFullName() + " declares no access"));
                }
            }
        };
    }
}

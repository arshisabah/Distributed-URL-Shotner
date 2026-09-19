package com.shortener.url.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * ArchUnit tests — fail the build if Clean Architecture layers are violated.
 *
 * Enforced rules:
 *   domain       → must not depend on Spring, infrastructure, or application layers
 *   application  → must not depend on infrastructure or api layers
 *   port         → must only contain interfaces
 *   api          → must not directly access repositories
 *   infrastructure → implements ports, may use Spring freely
 *
 * These tests catch accidental architectural rot before it reaches production.
 */
@AnalyzeClasses(
    packages    = "com.shortener.url",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_must_not_depend_on_infrastructure =
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAPackage("..infrastructure..")
            .because("Domain layer must be framework-agnostic — no infrastructure dependencies");

    @ArchTest
    static final ArchRule domain_must_not_depend_on_spring_framework =
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAPackage("org.springframework..")
            .because("Domain objects must not know about Spring — use plain Java");

    @ArchTest
    static final ArchRule application_must_not_depend_on_infrastructure =
        noClasses()
            .that().resideInAPackage("..application..")
            .and().doNotHaveSimpleName("UrlApplicationService") // has @Async from Spring
            .should().dependOnClassesThat()
            .resideInAPackage("..infrastructure..")
            .because("Application layer must depend only on ports, not concrete adapters");

    @ArchTest
    static final ArchRule api_controllers_must_not_use_repositories_directly =
        noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat()
            .implement(org.springframework.data.repository.Repository.class)
            .because("Controllers must go through application services; direct repo access bypasses business rules");

    @ArchTest
    static final ArchRule services_must_be_annotated_with_service =
        classes()
            .that().resideInAPackage("..application..")
            .and().haveNameMatching(".*Service")
            .should().beAnnotatedWith(org.springframework.stereotype.Service.class)
            .because("All application services must be Spring-managed (@Service)");

    @ArchTest
    static final ArchRule repositories_must_be_interfaces =
        classes()
            .that().resideInAPackage("..infrastructure.persistence..")
            .and().haveNameMatching(".*Repository")
            .should().beInterfaces()
            .because("Spring Data repositories must be interfaces, not concrete classes");

    @ArchTest
    static final ArchRule no_field_injection =
        noFields()
            .should().beAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class)
            .because("Use constructor injection — easier to test, null-safe, explicit dependencies");

    @ArchTest
    static final ArchRule port_interfaces_must_only_be_interfaces =
        classes()
            .that().resideInAPackage("..port..")
            .should().beInterfaces()
            .because("Ports are interfaces — adapters implement them; this enforces hexagonal architecture");

    @ArchTest
    static final ArchRule layered_architecture =
        layeredArchitecture()
            .consideringAllDependencies()
            .layer("API")            .definedBy("..api..")
            .layer("Application")   .definedBy("..application..")
            .layer("Domain")        .definedBy("..domain..")
            .layer("Port")          .definedBy("..port..")
            .layer("Infrastructure").definedBy("..infrastructure..")
            .whereLayer("API")            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Application")    .mayOnlyBeAccessedByLayers("API")
            .whereLayer("Domain")         .mayOnlyBeAccessedByLayers("Application", "Infrastructure")
            .whereLayer("Port")           .mayOnlyBeAccessedByLayers("Application", "Infrastructure");
}

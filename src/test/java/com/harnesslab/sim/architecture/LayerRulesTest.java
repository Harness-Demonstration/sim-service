package com.harnesslab.sim.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture rules from .github/copilot-instructions.md, as tests.
 * Each rule says how to fix a violation, so whoever reads the failure (a person or Copilot) knows what to do.
 */
@AnalyzeClasses(packages = "com.harnesslab.sim", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerRulesTest {

    @ArchTest
    static final ArchRule stream_and_api_do_not_call_repository = noClasses()
            .that().resideInAnyPackage("..stream..", "..api..")
            .should().dependOnClassesThat().resideInAPackage("..repository..")
            .because("decisions belong in service. FIX: move the logic into a service class, inject that service here "
                    + "and call one service method; only service may call repository.");

    @ArchTest
    static final ArchRule persistence_annotations_stay_in_repository = noClasses()
            .that().resideOutsideOfPackage("..repository..")
            .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..")
            .because("entities belong in repository. FIX: map entities to types records inside repository.");

    @ArchTest
    static final ArchRule jdbc_template_is_not_used = noClasses()
            .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
            .because("persistence uses Spring Data JPA. FIX: use a repository interface and keep SQL in Flyway migrations.");
}

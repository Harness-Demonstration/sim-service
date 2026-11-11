package com.harnesslab.sim.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Instant;

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

    @ArchTest
    static final ArchRule layers_depend_in_one_direction = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("types").definedBy("..types..")
            .layer("config").definedBy("..config..")
            .layer("repository").definedBy("..repository..")
            .layer("service").definedBy("..service..")
            .layer("stream").definedBy("..stream..")
            .layer("api").definedBy("..api..")
            .whereLayer("api").mayNotBeAccessedByAnyLayer()
            .whereLayer("stream").mayNotBeAccessedByAnyLayer()
            .whereLayer("config").mayNotBeAccessedByAnyLayer()
            .whereLayer("service").mayOnlyBeAccessedByLayers("api", "stream")
            .whereLayer("repository").mayOnlyBeAccessedByLayers("service")
            .because("dependencies point one way: types, config, repository, service, then stream and api (see ARCHITECTURE.md). "
                    + "FIX: move the code to the layer that may depend on what it needs, or invert the dependency with an interface in the lower layer.");

    @ArchTest
    static final ArchRule service_does_not_use_kafka = noClasses()
            .that().resideInAPackage("..service..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.kafka..")
            .because("events leave through the outbox (docs/design-docs/outbox.md). FIX: write an outbox row in the same transaction; "
                    + "only stream talks to Kafka.");

    @ArchTest
    static final ArchRule no_wall_clock = noClasses()
            .should().callMethod(Instant.class, "now")
            .because("time comes from the injected java.time.Clock so tests can fix it. FIX: inject Clock and call clock.instant().");
}

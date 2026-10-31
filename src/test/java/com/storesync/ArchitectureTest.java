package com.storesync;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.storesync", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsPlainJava = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..", "java.sql..",
                    "com.fasterxml.jackson..", "org.apache.kafka..", "..application..", "..adapter..");

    /** Money, tax and stock rules see only their own module's domain and the shared value types. */
    @ArchTest
    static final ArchRule domainOnlySeesDomain = classes().that().resideInAPackage("com.storesync.(*).domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("com.storesync.*.domain..", "java..", "");

    @ArchTest
    static final ArchRule applicationDoesNotReachIntoAdapters = noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..");

    @ArchTest
    static final ArchRule applicationDoesNotTouchInfrastructure = noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..",
                    "org.springframework.web..", "java.sql..", "org.springframework.kafka..", "org.apache.kafka..",
                    "org.springframework.data.redis..");

    @ArchTest
    static final ArchRule modulesHaveNoCycles = slices().matching("com.storesync.(*).application..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule controllersLiveInAdapters = classes().that()
            .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().resideInAPackage("..adapter..").andShould().notBePublic();
}

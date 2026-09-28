package com.divalhr.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Enforces the domain boundaries from docs/ARCHITECTURE.md. */
@AnalyzeClasses(
    packages = "com.divalhr.core",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

  private static final String[] MODULES = {
    "identity", "tenant", "people", "operations", "payroll",
    "documents", "integrations", "analytics", "finance"
  };

  @ArchTest
  static final ArchRule platformDoesNotDependOnModules =
      noClasses()
          .that()
          .resideInAPackage("com.divalhr.core.platform..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(modulePackages())
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule modulesDoNotDependOnEachOther =
      slices()
          .matching("com.divalhr.core.(*)..")
          .namingSlices("$1")
          .should()
          .notDependOnEachOther()
          .ignoreDependency(
              com.tngtech.archunit.base.DescribedPredicate.alwaysTrue(),
              com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage(
                  "com.divalhr.core.platform.."))
          .ignoreDependency(
              com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage(
                  "com.divalhr.core"),
              com.tngtech.archunit.base.DescribedPredicate.alwaysTrue())
          .allowEmptyShould(true);

  private static String[] modulePackages() {
    String[] packages = new String[MODULES.length];
    for (int i = 0; i < MODULES.length; i++) {
      packages[i] = "com.divalhr.core." + MODULES[i] + "..";
    }
    return packages;
  }
}

package com.divalhr.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.divalhr.core.platform.access.PayrollPaymentGuard;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** MVP-094C: layering of payroll approval, the payment guard and the self-contained PDF writer. */
@AnalyzeClasses(packages = "com.divalhr.core", importOptions = ImportOption.DoNotIncludeTests.class)
class PayrollPackageArchitectureTest {

  /** The payslip, digest and approval rules are pure: no Spring, no JDBC, no other layer. */
  @ArchTest
  static final ArchRule payrollDomainIsPure =
      noClasses()
          .that()
          .resideInAPackage("com.divalhr.core.payroll.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.divalhr.core.payroll.application..",
              "com.divalhr.core.payroll.internal..",
              "com.divalhr.core.payroll.api..",
              "org.springframework..",
              "jakarta..",
              "java.sql..");

  /** Controllers reach the repository only through the application layer. */
  @ArchTest
  static final ArchRule payrollControllersDoNotReachTheDatabaseLayer =
      noClasses()
          .that()
          .resideInAPackage("com.divalhr.core.payroll.api..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.divalhr.core.payroll.internal..");

  /** The package repository is used by the application layer only. */
  @ArchTest
  static final ArchRule payrollPackageRepositoryIsUsedByTheApplicationLayerOnly =
      noClasses()
          .that()
          .resideOutsideOfPackages(
              "com.divalhr.core.payroll.application..", "com.divalhr.core.payroll.internal..")
          .should()
          .dependOnClassesThat()
          .haveSimpleName("JdbcPayrollPackageRepository");

  /**
   * The PDF writer is repository-owned: it depends on the pure domain and the JDK only, so it can
   * load no remote asset, no font file and no third-party library.
   */
  @ArchTest
  static final ArchRule payslipRendererIsSelfContained =
      classes()
          .that()
          .haveSimpleName("PayslipPdfRenderer")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage(
              "com.divalhr.core.payroll.domain..",
              "com.divalhr.core.payroll.application..",
              "org.springframework.stereotype..",
              "java..");

  /** MVP-094C: the shipped guard reports no payment; only a future payment module replaces it. */
  @ArchTest
  static final ArchRule onlyTheNoPaymentGuardImplementsThePaymentPort =
      classes()
          .that()
          .implement(PayrollPaymentGuard.class)
          .should()
          .haveSimpleName("NoPaymentRecords");

  /** The void service depends on the port, never on its shipped implementation. */
  @ArchTest
  static final ArchRule nobodyDependsOnTheShippedPaymentGuardImplementation =
      noClasses()
          .that()
          .doNotHaveSimpleName("NoPaymentRecords")
          .should()
          .dependOnClassesThat()
          .haveSimpleName("NoPaymentRecords");
}

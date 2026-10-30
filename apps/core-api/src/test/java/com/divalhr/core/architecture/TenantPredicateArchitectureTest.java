package com.divalhr.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.divalhr.core.platform.tenancy.CrossTenantAccess;
import com.divalhr.core.platform.tenancy.TenantId;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Every public tenant repository operation takes the verified {@link TenantId}, so a query without
 * a tenant predicate cannot be written without failing the build. Organization provisioning (a
 * platform operation that creates the tenant) is the single, explicit exception.
 */
@AnalyzeClasses(packages = "com.divalhr.core", importOptions = ImportOption.DoNotIncludeTests.class)
class TenantPredicateArchitectureTest {

  private static final DescribedPredicate<JavaMethod> ORGANIZATION_PROVISIONING =
      DescribedPredicate.describe(
          "organization provisioning insert",
          method ->
              method.getOwner().getSimpleName().equals("JdbcOrganizationRepository")
                  && method.getName().equals("insert"));

  private static final ArchCondition<JavaMethod> TAKE_TENANT_ID =
      new ArchCondition<>("take a TenantId parameter") {
        @Override
        public void check(JavaMethod method, ConditionEvents events) {
          boolean scoped =
              method.getRawParameterTypes().stream()
                  .anyMatch(type -> type.isEquivalentTo(TenantId.class));
          events.add(
              new SimpleConditionEvent(
                  method, scoped, method.getFullName() + " must take the verified TenantId"));
        }
      };

  @ArchTest
  static final ArchRule tenantRepositoriesRequireTheVerifiedTenant =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("com.divalhr.core.tenant.internal..")
          .and()
          .areDeclaredInClassesThat()
          .haveSimpleNameEndingWith("Repository")
          .and()
          .arePublic()
          .and(DescribedPredicate.not(ORGANIZATION_PROVISIONING))
          .should(TAKE_TENANT_ID);

  /**
   * MVP-010: identity repositories follow the same rule. The anonymous token flow and background
   * jobs cannot have a verified tenant; each such operation must say why with {@link
   * CrossTenantAccess}.
   */
  @ArchTest
  static final ArchRule identityRepositoriesRequireTheVerifiedTenant =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("com.divalhr.core.identity.internal..")
          .and()
          .areDeclaredInClassesThat()
          .haveSimpleNameEndingWith("Repository")
          .and()
          .arePublic()
          .and()
          .areNotAnnotatedWith(CrossTenantAccess.class)
          .should(TAKE_TENANT_ID);

  /**
   * MVP-020: people repositories follow the same rule; the import expiry and retention jobs are
   * marked {@link CrossTenantAccess}.
   */
  @ArchTest
  static final ArchRule peopleRepositoriesRequireTheVerifiedTenant =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("com.divalhr.core.people.internal..")
          .and()
          .areDeclaredInClassesThat()
          .haveSimpleNameEndingWith("Repository")
          .and()
          .arePublic()
          .and()
          .areNotAnnotatedWith(CrossTenantAccess.class)
          .should(TAKE_TENANT_ID);

  /** MVP-040A: the leave package's repositories follow the same rule, with no exception. */
  @ArchTest
  static final ArchRule leaveRepositoriesRequireTheVerifiedTenant =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("com.divalhr.core.people.leave.internal..")
          .and()
          .areDeclaredInClassesThat()
          .haveSimpleNameEndingWith("Repository")
          .and()
          .arePublic()
          .should(TAKE_TENANT_ID);

  /** MVP-050A: the scheduling package's repositories follow the same rule, with no exception. */
  @ArchTest
  static final ArchRule scheduleRepositoriesRequireTheVerifiedTenant =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("com.divalhr.core.people.schedule.internal..")
          .and()
          .areDeclaredInClassesThat()
          .haveSimpleNameEndingWith("Repository")
          .and()
          .arePublic()
          .should(TAKE_TENANT_ID);

  /**
   * MVP-030: documents repositories follow the same rule; the read-only contract integrity job is
   * marked {@link CrossTenantAccess}.
   */
  @ArchTest
  static final ArchRule documentsRepositoriesRequireTheVerifiedTenant =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("com.divalhr.core.documents.internal..")
          .and()
          .areDeclaredInClassesThat()
          .haveSimpleNameEndingWith("Repository")
          .and()
          .arePublic()
          .and()
          .areNotAnnotatedWith(CrossTenantAccess.class)
          .should(TAKE_TENANT_ID);

  /** MVP-080: the operations repositories follow the same rule, with no exception. */
  @ArchTest
  static final ArchRule operationsRepositoriesRequireTheVerifiedTenant =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("com.divalhr.core.operations.internal..")
          .and()
          .areDeclaredInClassesThat()
          .haveSimpleNameEndingWith("Repository")
          .and()
          .arePublic()
          .should(TAKE_TENANT_ID);

  /**
   * MVP-093A: the compensation repository follows the same rule, with no exception (every statement
   * filters on the tenant and the employee). The older payroll-input repositories keep their
   * MVP-090A shape.
   */
  @ArchTest
  static final ArchRule compensationRepositoryRequiresTheVerifiedTenant =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("com.divalhr.core.payroll.internal..")
          .and()
          .areDeclaredInClassesThat()
          .haveSimpleName("JdbcCompensationRepository")
          .and()
          .arePublic()
          .should(TAKE_TENANT_ID);

  /**
   * MVP-094A: the payroll run repository follows the same rule, with no exception (every statement
   * filters on the tenant and the legal entity or run).
   */
  @ArchTest
  static final ArchRule payrollRunRepositoryRequiresTheVerifiedTenant =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("com.divalhr.core.payroll.internal..")
          .and()
          .areDeclaredInClassesThat()
          .haveSimpleName("JdbcPayrollRunRepository")
          .and()
          .arePublic()
          .should(TAKE_TENANT_ID);

  /**
   * MVP-094B: the tenant-owned statutory repository follows the same rule, with no exception (every
   * statement filters on the tenant).
   */
  @ArchTest
  static final ArchRule statutoryRepositoryRequiresTheVerifiedTenant =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("com.divalhr.core.payroll.internal..")
          .and()
          .areDeclaredInClassesThat()
          .haveSimpleName("JdbcStatutoryRepository")
          .and()
          .arePublic()
          .should(TAKE_TENANT_ID);

  /**
   * MVP-094B: the rule pack repository is the single, explicit exception: rule packs are
   * platform-owned reference data with no tenant column, so none of its operations takes a tenant
   * (JdbcStatutoryRepositoryTenantTest also asserts that no statement there names a tenant table).
   */
  @ArchTest
  static final ArchRule rulePackRepositoryIsPlatformOwnedAndTakesNoTenant =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("com.divalhr.core.payroll.internal..")
          .and()
          .areDeclaredInClassesThat()
          .haveSimpleName("JdbcStatutoryRulePackRepository")
          .and()
          .arePublic()
          .should(
              new ArchCondition<JavaMethod>("not take a TenantId parameter (platform data)") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                  boolean scoped =
                      method.getRawParameterTypes().stream()
                          .anyMatch(type -> type.isEquivalentTo(TenantId.class));
                  events.add(
                      new SimpleConditionEvent(
                          method,
                          !scoped,
                          method.getFullName() + " must not take a TenantId: platform data"));
                }
              });
}

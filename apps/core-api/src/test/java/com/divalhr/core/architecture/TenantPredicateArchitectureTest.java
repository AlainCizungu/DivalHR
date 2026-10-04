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
}

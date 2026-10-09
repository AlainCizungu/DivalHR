package com.divalhr.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.divalhr.core.people.api.StrictRequest;
import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.ManagerGraphLock;
import com.divalhr.core.people.application.PeopleCaller;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * MVP-040A (D40A-1): leave is a bounded package of the people module. It uses the platform and only
 * four people types (the business calendar, the verified caller, the strict-request base and, from
 * MVP-041B (D41B-5), the manager-graph lock, so the lock key has one definition); nothing else in
 * the application depends on it.
 */
@AnalyzeClasses(packages = "com.divalhr.core", importOptions = ImportOption.DoNotIncludeTests.class)
class LeavePackageBoundaryTest {

  private static final String LEAVE = "com.divalhr.core.people.leave..";

  private static final DescribedPredicate<JavaClass> ALLOWED_PEOPLE_TYPES =
      DescribedPredicate.describe(
          "the business calendar, the verified caller, the strict-request base or the"
              + " manager-graph lock",
          type ->
              type.isEquivalentTo(BusinessCalendar.class)
                  || type.isEquivalentTo(PeopleCaller.class)
                  || type.isEquivalentTo(StrictRequest.class)
                  || type.isEquivalentTo(ManagerGraphLock.class));

  @ArchTest
  static final ArchRule leaveUsesOnlyThePlatformAndFourPeopleTypes =
      noClasses()
          .that()
          .resideInAPackage(LEAVE)
          .should()
          .dependOnClassesThat(
              DescribedPredicate.and(
                  JavaClass.Predicates.resideInAPackage("com.divalhr.core.."),
                  DescribedPredicate.not(JavaClass.Predicates.resideInAPackage(LEAVE)),
                  DescribedPredicate.not(
                      JavaClass.Predicates.resideInAPackage("com.divalhr.core.platform..")),
                  DescribedPredicate.not(ALLOWED_PEOPLE_TYPES)));

  @ArchTest
  static final ArchRule nothingOutsideDependsOnLeave =
      classes()
          .that()
          .resideInAPackage(LEAVE)
          .should()
          .onlyHaveDependentClassesThat()
          .resideInAPackage(LEAVE);
}

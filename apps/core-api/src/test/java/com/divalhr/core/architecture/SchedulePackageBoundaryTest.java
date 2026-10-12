package com.divalhr.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.divalhr.core.people.api.StrictRequest;
import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.ManagerGraphLock;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.domain.EmployeeSearchKey;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * MVP-050A (ADR 0014): scheduling is a bounded package of the people module. It uses the platform
 * and only a few people types (the business calendar, the verified caller and the strict-request
 * base; MVP-051, ADR 0015: the tenant's manager-graph lock, the shared lock order of every
 * reporting-line decision, and the employee search key, the directory's name normalization); it
 * never depends on the leave package or on the tenant module (sites come through the platform
 * port), and nothing else in the application depends on it.
 */
@AnalyzeClasses(packages = "com.divalhr.core", importOptions = ImportOption.DoNotIncludeTests.class)
class SchedulePackageBoundaryTest {

  private static final String SCHEDULE = "com.divalhr.core.people.schedule..";

  private static final DescribedPredicate<JavaClass> ALLOWED_PEOPLE_TYPES =
      DescribedPredicate.describe(
          "the business calendar, the verified caller, the strict-request base, the manager-graph"
              + " lock or the employee search key",
          type ->
              type.isEquivalentTo(BusinessCalendar.class)
                  || type.isEquivalentTo(PeopleCaller.class)
                  || type.isEquivalentTo(StrictRequest.class)
                  || type.isEquivalentTo(ManagerGraphLock.class)
                  || type.isEquivalentTo(EmployeeSearchKey.class));

  @ArchTest
  static final ArchRule scheduleUsesOnlyThePlatformAndTheAllowedPeopleTypes =
      noClasses()
          .that()
          .resideInAPackage(SCHEDULE)
          .should()
          .dependOnClassesThat(
              DescribedPredicate.and(
                  JavaClass.Predicates.resideInAPackage("com.divalhr.core.."),
                  DescribedPredicate.not(JavaClass.Predicates.resideInAPackage(SCHEDULE)),
                  DescribedPredicate.not(
                      JavaClass.Predicates.resideInAPackage("com.divalhr.core.platform..")),
                  DescribedPredicate.not(ALLOWED_PEOPLE_TYPES)));

  @ArchTest
  static final ArchRule nothingOutsideDependsOnSchedule =
      classes()
          .that()
          .resideInAPackage(SCHEDULE)
          .should()
          .onlyHaveDependentClassesThat()
          .resideInAPackage(SCHEDULE);
}

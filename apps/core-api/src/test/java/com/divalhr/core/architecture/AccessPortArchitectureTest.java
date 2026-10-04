package com.divalhr.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmploymentContractFacts;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A22-4: the people separation service is the only transaction coordinator. Every identity port
 * method it calls that locks or writes joins the existing transaction ({@code MANDATORY}) and never
 * opens its own; and neither module queries the other's tables (the cross-schema foreign keys of
 * ADR 0008 are constraints, never reads). MVP-030 (ADR 0009): the documents module coordinates
 * issue and acknowledgement the same way, through the locking people and identity port methods, and
 * no module names another's tables.
 */
@AnalyzeClasses(packages = "com.divalhr.core", importOptions = ImportOption.DoNotIncludeTests.class)
class AccessPortArchitectureTest {

  private static final Set<String> JOINING =
      Set.of(
          "lockForSeparation", "scheduleRevocation", "cancelRevocation", "retry", "linkedEmployee");

  private static final ArchCondition<JavaMethod> JOIN_THE_CALLER =
      new ArchCondition<>("join the caller's transaction (MANDATORY)") {
        @Override
        public void check(JavaMethod method, ConditionEvents events) {
          boolean mandatory =
              method.isAnnotatedWith(Transactional.class)
                  && method.getAnnotationOfType(Transactional.class).propagation()
                      == Propagation.MANDATORY;
          events.add(new SimpleConditionEvent(method, mandatory, method.getFullName()));
        }
      };

  @ArchTest
  static final ArchRule identityPortMethodsJoinTheCoordinatorsTransaction =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .implement(EmployeeAccessLinks.class)
          .and()
          .haveNameMatching(
              "lockForSeparation|scheduleRevocation|cancelRevocation|retry|linkedEmployee")
          .should(JOIN_THE_CALLER);

  /** MVP-030: the employment lock of an issue joins the documents transaction. */
  @ArchTest
  static final ArchRule peopleContractPortLocksJoinTheCoordinatorsTransaction =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .implement(EmploymentContractFacts.class)
          .and()
          .haveName("lockForContract")
          .should(JOIN_THE_CALLER);

  @Test
  void theContractPortLocksOnlyThroughItsJoiningMethod() {
    assertThat(
            Stream.of(EmploymentContractFacts.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .toList())
        .containsExactlyInAnyOrder("read", "lockForContract");
  }

  @Test
  void theSetOfJoiningMethodsCoversEveryWritingPortMethod() {
    List<String> declared =
        Stream.of(EmployeeAccessLinks.class.getDeclaredMethods())
            .map(java.lang.reflect.Method::getName)
            .filter(name -> !name.equals("activeLink") && !name.equals("revocations"))
            .toList();
    assertThat(JOINING).containsExactlyInAnyOrderElementsOf(declared);
  }

  private static final Pattern IDENTITY_TABLE =
      Pattern.compile(
          "\\bidentity\\.(tenant_membership|employee_access_link|access_revocation|invitation)\\b");
  private static final Pattern PEOPLE_TABLE =
      Pattern.compile(
          "\\bpeople\\.(employee|employment|employment_[a-z_]+|separation_task[a-z_]*)\\b");

  private static final Pattern DOCUMENTS_TABLE =
      Pattern.compile("\\bdocuments\\.(contract[a-z_]*)\\b");

  @Test
  void neitherModuleQueriesTheOthersTables() throws IOException {
    Path root = Path.of("src/main/java/com/divalhr/core");
    List<String> offenders = new ArrayList<>();
    scan(root.resolve("people"), IDENTITY_TABLE, offenders);
    scan(root.resolve("identity"), PEOPLE_TABLE, offenders);
    scan(root.resolve("documents"), IDENTITY_TABLE, offenders);
    scan(root.resolve("documents"), PEOPLE_TABLE, offenders);
    scan(root.resolve("people"), DOCUMENTS_TABLE, offenders);
    scan(root.resolve("identity"), DOCUMENTS_TABLE, offenders);
    assertThat(offenders).isEmpty();
  }

  private static void scan(Path module, Pattern foreign, List<String> offenders)
      throws IOException {
    try (Stream<Path> files = Files.walk(module)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (foreign.matcher(text).find()) {
          offenders.add(file.toString());
        }
      }
    }
  }
}

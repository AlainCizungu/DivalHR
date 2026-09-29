package com.divalhr.core.platform.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.tenant.application.CreateLegalEntityService;
import com.divalhr.core.tenant.application.CreateOrganizationService;
import com.divalhr.core.tenant.application.CreateSiteService;
import com.divalhr.core.tenant.application.HierarchyQueryService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class OperationNameTest {

  /** Every operation and audit-action name the application uses today. */
  static final List<String> CURRENT =
      List.of(
          CreateOrganizationService.OPERATION,
          CreateLegalEntityService.OPERATION,
          CreateSiteService.OPERATION,
          HierarchyQueryService.LIST_LEGAL_ENTITIES,
          HierarchyQueryService.LIST_SITES);

  /** Malformed names from Issue #17 plus the grammar's edges. */
  static final List<String> MALFORMED =
      List.of("a.-", "a.b-", "a.-b", "a..b", "a.b--c", "-a.b", "A.b", "a", "x.---", "a.b.", "");

  @Test
  void acceptsEveryCurrentNameAndRejectsMalformedOnes() {
    assertThat(CURRENT).allMatch(OperationName::isValid);
    assertThat(MALFORMED).noneMatch(OperationName::isValid);
    assertThat(OperationName.isValid(null)).isFalse();
  }

  @Test
  void requireNeverEchoesTheRejectedValue() {
    assertThatThrownBy(() -> OperationName.require("a..secret", "audit action"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("secret");
  }

  @Test
  void v4MigrationUsesExactlyTheSharedGrammar() throws IOException {
    String sql =
        Files.readString(
            Path.of("src/main/resources/db/migration/V4__tighten_operation_name_checks.sql"));
    // Two pre-flight predicates and two constraints, all the same literal.
    assertThat(sql.split("'" + java.util.regex.Pattern.quote(OperationName.GRAMMAR) + "'", -1))
        .hasSize(5);
  }

  @Test
  void theGrammarIsDefinedOnlyOnce() throws IOException {
    String fragment = "(\\\\.[a-z]+(-[a-z]+)*)+$";
    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      List<String> holders =
          files
              .filter(path -> path.toString().endsWith(".java"))
              .filter(path -> read(path).contains(fragment))
              .map(path -> path.getFileName().toString())
              .toList();
      assertThat(holders).containsExactly("OperationName.java");
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}

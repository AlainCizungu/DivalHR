package com.divalhr.core.payroll.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * MVP-097A: the governance repository serves platform data. No statement names a tenant or a
 * tenant-owned payroll table, and every statement addresses a statutory pack table.
 */
class JdbcStatutoryPackGovernanceRepositoryScopeTest {

  private static final Path SOURCE =
      Path.of(
          "src/main/java/com/divalhr/core/payroll/internal/"
              + "JdbcStatutoryPackGovernanceRepository.java");

  private static List<String> statements() throws IOException {
    String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
    List<String> found = new ArrayList<>();
    Matcher start = Pattern.compile("(jdbc|batch)\\s*\\.\\s*(sql|batchUpdate)\\(").matcher(source);
    while (start.find()) {
      int depth = 1;
      boolean quoted = false;
      int i = start.end();
      while (depth > 0) {
        char c = source.charAt(i);
        if (quoted) {
          if (c == '\\') {
            i++;
          } else if (c == '"') {
            quoted = false;
          }
        } else if (c == '"') {
          quoted = true;
        } else if (c == '(') {
          depth++;
        } else if (c == ')') {
          depth--;
        }
        i++;
      }
      found.add(source.substring(start.end(), i - 1));
    }
    return found;
  }

  @Test
  void noStatementNamesATenantOrATenantOwnedTable() throws IOException {
    List<String> statements = statements();
    assertThat(statements).hasSizeGreaterThan(15);
    for (String statement : statements) {
      // The only statement without a table is the per-transaction timeouts.
      if (statement.contains("SET LOCAL ")) {
        continue;
      }
      assertThat(statement)
          .as(statement)
          .doesNotContain("tenant")
          .doesNotContain("statutory_activation")
          .doesNotContain("statutory_profile")
          .doesNotContain("statutory_calculation")
          .doesNotContain("pay_run")
          .satisfiesAnyOf(
              text -> assertThat(text).contains("payroll.statutory_"),
              text -> assertThat(text).contains("VERSION_FROM"));
    }
  }

  @Test
  void theSharedStatementFragmentsNameStatutoryTablesAndNoTenant() throws IOException {
    String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
    Matcher fragment =
        Pattern.compile("VERSION_(?:COLUMNS|FROM)\\s*=\\s*((?:\\s*\"[^\"]*\"\\s*\\+?)+);")
            .matcher(source);
    StringBuilder all = new StringBuilder();
    while (fragment.find()) {
      all.append(fragment.group(1));
    }
    assertThat(all.toString()).contains("payroll.statutory_rule_pack").doesNotContain("tenant");
  }

  @Test
  void theRepositoryNeverMentionsATenantIdentifierType() throws IOException {
    String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
    assertThat(source)
        .doesNotContain("import com.divalhr.core.platform.tenancy")
        .doesNotContain("TenantId ");
  }
}

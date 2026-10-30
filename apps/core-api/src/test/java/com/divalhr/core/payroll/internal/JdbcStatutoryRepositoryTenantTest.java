package com.divalhr.core.payroll.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.tenancy.TenantId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.MappedQuerySpec;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * MVP-094B: the statutory chain is always read inside the verified tenant, every statement of the
 * tenant repository names the tenant, the platform rule pack repository names no tenant (its tables
 * have none), and constraints map to stable codes.
 */
class JdbcStatutoryRepositoryTenantTest {

  private static final Path SOURCES = Path.of("src/main/java/com/divalhr/core/payroll/internal");

  @Test
  @SuppressWarnings("unchecked")
  void theChainVersionIsReadWithTheTenantPredicateAndTheTenantParameter() {
    JdbcClient jdbc = mock(JdbcClient.class);
    StatementSpec statement = mock(StatementSpec.class);
    MappedQuerySpec<Integer> query = mock(MappedQuerySpec.class);
    when(jdbc.sql(anyString())).thenReturn(statement);
    when(statement.param(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(statement);
    when(statement.query(Integer.class)).thenReturn(query);
    when(query.single()).thenReturn(3);
    TenantId tenant = TenantId.parse("11111111-1111-4111-8111-111111111111");
    UUID run = UUID.fromString("22222222-2222-4222-8222-222222222222");
    UUID pack = UUID.fromString("33333333-3333-4333-8333-333333333333");

    int version =
        new JdbcStatutoryRepository(jdbc, mock(NamedParameterJdbcTemplate.class))
            .chainVersion(tenant, run, pack);

    assertThat(version).isEqualTo(3);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).sql(sql.capture());
    assertThat(sql.getValue()).contains("tenant_id = :tenant").contains("run_id = :run");
    verify(statement).param(eq("tenant"), eq(tenant.value()));
    verify(statement).param(eq("run"), eq(run));
    verify(statement).param(eq("pack"), eq(pack));
  }

  /** The text of every statement passed to {@code jdbc.sql(} or {@code batch.batchUpdate(}. */
  private static List<String> statements(String file) throws IOException {
    String source = Files.readString(SOURCES.resolve(file), StandardCharsets.UTF_8);
    List<String> found = new ArrayList<>();
    java.util.regex.Matcher start =
        java.util.regex.Pattern.compile("(jdbc|batch)\\s*\\.\\s*(sql|batchUpdate)\\(")
            .matcher(source);
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
  void everyStatementOfTheTenantRepositoryNamesTheTenant() throws IOException {
    List<String> statements = statements("JdbcStatutoryRepository.java");
    assertThat(statements).hasSizeGreaterThan(20);
    for (String statement : statements) {
      // The only statement without a table is the deferred-constraint switch of the transaction.
      if (statement.contains("SET CONSTRAINTS")) {
        continue;
      }
      assertThat(statement).as(statement).contains("tenant");
    }
  }

  @Test
  void theRulePackRepositoryIsPlatformDataAndNamesNoTenantOrTenantTable() throws IOException {
    List<String> statements = statements("JdbcStatutoryRulePackRepository.java");
    assertThat(statements).isNotEmpty();
    for (String statement : statements) {
      assertThat(statement)
          .as(statement)
          .doesNotContain("tenant")
          .doesNotContain("statutory_activation")
          .doesNotContain("statutory_profile")
          .doesNotContain("statutory_calculation")
          .doesNotContain("pay_run")
          .contains("payroll.statutory_rule_pack");
      assertThat(statement).as(statement).doesNotContainPattern("(?i)\\b(insert|update|delete)\\b");
    }
  }

  @Test
  void everyMappedConstraintHasAStableAnswerAndADefectHasNone() {
    for (String name : StatutoryConstraintViolations.LIMITS) {
      assertThat(StatutoryConstraintViolations.answer(name))
          .contains(ErrorCode.STATUTORY_LIMIT_REACHED);
    }
    for (String name : StatutoryConstraintViolations.VERSION_CONFLICT) {
      assertThat(StatutoryConstraintViolations.answer(name))
          .contains(ErrorCode.STATUTORY_VERSION_CONFLICT);
    }
    for (String name : StatutoryConstraintViolations.PACK_NOT_VERIFIED) {
      assertThat(StatutoryConstraintViolations.answer(name))
          .contains(ErrorCode.STATUTORY_RULE_PACK_NOT_VERIFIED);
    }
    for (String name : StatutoryConstraintViolations.NOT_APPLICABLE) {
      assertThat(StatutoryConstraintViolations.answer(name))
          .contains(ErrorCode.STATUTORY_RULE_PACK_NOT_APPLICABLE);
    }
    for (String name : StatutoryConstraintViolations.STALE) {
      assertThat(StatutoryConstraintViolations.answer(name))
          .contains(ErrorCode.STATUTORY_PREVIEW_STALE);
    }
    assertThat(StatutoryConstraintViolations.answer("payroll_statutory_calculation_exact"))
        .isEmpty();
    assertThat(StatutoryConstraintViolations.answer("something_else")).isEmpty();
    assertThat(StatutoryConstraintViolations.constraint(new IllegalStateException("x"))).isEmpty();
  }
}

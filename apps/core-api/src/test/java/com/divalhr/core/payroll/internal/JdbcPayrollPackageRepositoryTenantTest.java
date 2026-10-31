package com.divalhr.core.payroll.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository.PackageRow;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository.SlipRow;
import com.divalhr.core.platform.tenancy.TenantId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.MappedQuerySpec;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/** MVP-094C: every package statement names the verified tenant, self-service the employee. */
class JdbcPayrollPackageRepositoryTenantTest {

  private static final Path SOURCE =
      Path.of("src/main/java/com/divalhr/core/payroll/internal/JdbcPayrollPackageRepository.java");

  private static final TenantId TENANT = TenantId.parse("11111111-1111-4111-8111-111111111111");
  private static final UUID ENTITY = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID EMPLOYEE = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID SLIP = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID RUN = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID CALCULATION = UUID.fromString("66666666-6666-4666-8666-666666666666");

  /** A mocked JDBC client whose single statement and mapped query can be inspected. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static final class Fixture {
    final JdbcClient jdbc = mock(JdbcClient.class);
    final StatementSpec statement = mock(StatementSpec.class);
    final MappedQuerySpec query = mock(MappedQuerySpec.class);

    Fixture() {
      when(jdbc.sql(anyString())).thenReturn(statement);
      when(statement.param(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(statement);
      when(statement.query(org.mockito.ArgumentMatchers.any(RowMapper.class))).thenReturn(query);
      when(query.optional()).thenReturn(Optional.empty());
      when(query.list()).thenReturn(List.of());
    }

    JdbcPayrollPackageRepository repository() {
      return new JdbcPayrollPackageRepository(jdbc, mock(NamedParameterJdbcTemplate.class));
    }

    String sql() {
      ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
      verify(jdbc).sql(sql.capture());
      return sql.getValue();
    }
  }

  @Test
  void theChainOfAPeriodIsReadWithTheTenantAndTheLegalEntityPredicates() {
    Fixture fixture = new Fixture();
    List<PackageRow> chain =
        fixture.repository().chain(TENANT, ENTITY, LocalDate.parse("2026-03-01"), "CDF");

    assertThat(chain).isEmpty();
    assertThat(fixture.sql())
        .contains("p.tenant_id = :tenant")
        .contains("p.legal_entity_id = :entity")
        .contains("p.period_start = :period")
        .contains("p.currency = :currency");
    verify(fixture.statement).param(eq("tenant"), eq(TENANT.value()));
    verify(fixture.statement).param(eq("entity"), eq(ENTITY));
  }

  @Test
  void aPackageIsReadInsideItsTenantAndLegalEntity() {
    Fixture fixture = new Fixture();
    Optional<PackageRow> found = fixture.repository().pack(TENANT, ENTITY, RUN);

    assertThat(found).isEmpty();
    assertThat(fixture.sql())
        .contains("p.tenant_id = :tenant")
        .contains("p.legal_entity_id = :entity")
        .contains("p.id = :id");
    verify(fixture.statement).param(eq("tenant"), eq(TENANT.value()));
    verify(fixture.statement).param(eq("id"), eq(RUN));
  }

  @Test
  void thePackagesOfAPairAreReadInsideTheTenant() {
    Fixture fixture = new Fixture();
    fixture.repository().packagesOfPair(TENANT, RUN, CALCULATION);

    assertThat(fixture.sql()).contains("p.tenant_id = :tenant").contains("p.run_id = :run");
    verify(fixture.statement).param(eq("tenant"), eq(TENANT.value()));
    verify(fixture.statement).param(eq("run"), eq(RUN));
    verify(fixture.statement).param(eq("calculation"), eq(CALCULATION));
  }

  @Test
  void anAdministratorPayslipIsReadInsideItsTenantLegalEntityAndPackage() {
    Fixture fixture = new Fixture();
    Optional<SlipRow> found = fixture.repository().slip(TENANT, ENTITY, RUN, SLIP);

    assertThat(found).isEmpty();
    assertThat(fixture.sql())
        .contains("s.tenant_id = :tenant")
        .contains("s.legal_entity_id = :entity")
        .contains("s.package_id = :package")
        .contains("s.id = :id");
    verify(fixture.statement).param(eq("tenant"), eq(TENANT.value()));
    verify(fixture.statement).param(eq("package"), eq(RUN));
  }

  @Test
  void anEmployeePayslipIsReadWithTheTenantAndTheEmployeePredicate() {
    Fixture fixture = new Fixture();
    Optional<SlipRow> found = fixture.repository().mySlip(TENANT, EMPLOYEE, SLIP);

    assertThat(found).isEmpty();
    assertThat(fixture.sql())
        .contains("s.tenant_id = :tenant")
        .contains("s.employee_id = :employee")
        .contains("s.id = :id");
    verify(fixture.statement).param(eq("tenant"), eq(TENANT.value()));
    verify(fixture.statement).param(eq("employee"), eq(EMPLOYEE));
    verify(fixture.statement).param(eq("id"), eq(SLIP));
  }

  @Test
  void theEmployeePayslipListIsAlwaysScopedToTheEmployee() {
    Fixture first = new Fixture();
    first.repository().mySlipPage(TENANT, EMPLOYEE, null, 21);
    assertThat(first.sql())
        .contains("s.tenant_id = :tenant")
        .contains("s.employee_id = :employee")
        .contains("ORDER BY s.approved_at DESC, s.id DESC");
    verify(first.statement).param(eq("employee"), eq(EMPLOYEE));

    Fixture next = new Fixture();
    next.repository()
        .mySlipPage(
            TENANT,
            EMPLOYEE,
            new JdbcPayrollPackageRepository.EmployeePosition(
                java.time.Instant.parse("2026-04-01T10:00:00Z"), SLIP),
            21);
    assertThat(next.sql())
        .contains("s.tenant_id = :tenant")
        .contains("s.employee_id = :employee")
        .contains("s.approved_at < :at");
    verify(next.statement).param(eq("employee"), eq(EMPLOYEE));
  }

  /** The text of every statement passed to {@code jdbc.sql(} or {@code batch.batchUpdate(}. */
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
  void everyStatementOfTheRepositoryNamesTheTenant() throws IOException {
    List<String> statements = statements();
    assertThat(statements).hasSizeGreaterThan(20);
    String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
    for (String statement : statements) {
      // The only statement without a table is the deferred-constraint switch of the transaction.
      if (statement.contains("SET CONSTRAINTS")) {
        continue;
      }
      // The package page builds its statement with a builder whose first clause names the tenant.
      if (statement.strip().equals("sql.toString()")) {
        assertThat(source)
            .contains("\" WHERE p.tenant_id = :tenant AND p.legal_entity_id = :entity\"");
        continue;
      }
      // The advisory lock names the tenant in its key; every table statement filters on it.
      assertThat(statement).as(statement).contains(":tenant");
      if (!statement.contains("pg_advisory_xact_lock")) {
        assertThat(statement).as(statement).contains("tenant_id");
      }
    }
  }

  @Test
  void everyReadThroughASharedFromClauseFiltersOnTheTenantOfItsTable() throws IOException {
    for (String statement : statements()) {
      if (statement.contains("SLIP_FROM")) {
        assertThat(statement).as(statement).contains("s.tenant_id = :tenant");
      }
      if (statement.contains("PACKAGE_FROM")) {
        assertThat(statement).as(statement).contains("p.tenant_id = :tenant");
      }
    }
  }

  @Test
  void theSelfServiceStatementsAllCarryTheEmployeePredicate() throws IOException {
    int selfService = 0;
    for (String statement : statements()) {
      if (statement.contains("s.employee_id = :employee")) {
        selfService++;
        assertThat(statement).as(statement).contains("s.tenant_id = :tenant");
      }
    }
    // mySlip, and the first and the next page of mySlipPage.
    assertThat(selfService).isEqualTo(3);
  }

  @Test
  void theRepositoryNeverDeletesAndOnlyEverUpdatesThePackageStatus() throws IOException {
    int updates = 0;
    for (String statement : statements()) {
      assertThat(statement).as(statement).doesNotContainPattern("(?i)\\b(delete|truncate)\\b");
      if (statement.matches("(?is)\\s*\"UPDATE .*")) {
        updates++;
        assertThat(statement)
            .contains("UPDATE payroll.payroll_package SET status = 'VOIDED'")
            .contains("tenant_id = :tenant")
            .contains("status = 'APPROVED'");
      }
      assertThat(statement)
          .as(statement)
          .doesNotContainPattern("(?i)update\\s+payroll\\.payroll_payslip");
      assertThat(statement)
          .as(statement)
          .doesNotContainPattern("(?i)update\\s+payroll\\.payroll_package_(source|void)");
    }
    assertThat(updates).isEqualTo(1);
  }

  @Test
  void theSourceLocksAreTakenOnTheTenantsOwnRows() throws IOException {
    List<String> locks = new ArrayList<>();
    for (String statement : statements()) {
      if (statement.contains("FOR UPDATE")) {
        locks.add(statement);
        assertThat(statement).as(statement).contains("tenant_id = :tenant");
      }
    }
    assertThat(locks).hasSizeGreaterThan(3);
  }
}

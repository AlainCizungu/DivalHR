package com.divalhr.core.payroll.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.MappedQuerySpec;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/** The payroll run chain is always read inside the verified tenant; constraints map to codes. */
class JdbcPayrollRunRepositoryTenantTest {

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
    UUID entity = UUID.fromString("22222222-2222-4222-8222-222222222222");

    int version =
        new JdbcPayrollRunRepository(jdbc, mock(NamedParameterJdbcTemplate.class))
            .chainVersion(
                tenant,
                entity,
                java.time.LocalDate.parse("2026-05-01"),
                com.divalhr.core.payroll.domain.CompensationCurrency.USD);

    assertThat(version).isEqualTo(3);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).sql(sql.capture());
    assertThat(sql.getValue())
        .contains("tenant_id = :tenant")
        .contains("legal_entity_id = :entity");
    verify(statement).param(eq("tenant"), eq(tenant.value()));
    verify(statement).param(eq("entity"), eq(entity));
  }

  @Test
  void everyMappedConstraintHasAStableAnswerAndADefectHasNone() {
    assertThat(PayrollRunConstraintViolations.answer("payroll_run_policy_sequence_range"))
        .contains(ErrorCode.PAYROLL_RUN_LIMIT_REACHED);
    assertThat(PayrollRunConstraintViolations.answer("payroll_pay_run_version_range"))
        .contains(ErrorCode.PAYROLL_RUN_LIMIT_REACHED);
    for (String name : PayrollRunConstraintViolations.POLICY_CONFLICT) {
      assertThat(PayrollRunConstraintViolations.answer(name)).contains(ErrorCode.PAYROLL_POLICY_CONFLICT);
    }
    for (String name : PayrollRunConstraintViolations.RUN_CONFLICT) {
      assertThat(PayrollRunConstraintViolations.answer(name))
          .contains(ErrorCode.PAYROLL_RUN_VERSION_CONFLICT);
    }
    assertThat(PayrollRunConstraintViolations.answer("payroll_pay_run_policy_in_force"))
        .contains(ErrorCode.PAYROLL_RUN_PREVIEW_STALE);
    assertThat(PayrollRunConstraintViolations.answer("payroll_pay_run_line_gross")).isEmpty();
    assertThat(PayrollRunConstraintViolations.answer("payroll_pay_run_exact")).isEmpty();
    assertThat(PayrollRunConstraintViolations.constraint(new IllegalStateException("x"))).isEmpty();
  }
}

package com.divalhr.core.payroll.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divalhr.core.platform.tenancy.TenantId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.MappedQuerySpec;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/** The compensation version of an employee is always read inside the verified tenant. */
class JdbcCompensationRepositoryTenantTest {

  @Test
  @SuppressWarnings("unchecked")
  void theVersionIsReadWithTheTenantPredicateAndTheTenantParameter() {
    JdbcClient jdbc = mock(JdbcClient.class);
    StatementSpec statement = mock(StatementSpec.class);
    MappedQuerySpec<Integer> query = mock(MappedQuerySpec.class);
    when(jdbc.sql(anyString())).thenReturn(statement);
    when(statement.param(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(statement);
    when(statement.query(Integer.class)).thenReturn(query);
    when(query.single()).thenReturn(3);
    TenantId tenant = TenantId.parse("11111111-1111-4111-8111-111111111111");
    UUID employee = UUID.fromString("22222222-2222-4222-8222-222222222222");

    int version = new JdbcCompensationRepository(jdbc).version(tenant, employee);

    assertThat(version).isEqualTo(3);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).sql(sql.capture());
    assertThat(sql.getValue()).contains("tenant_id = :tenant").contains("employee_id = :employee");
    verify(statement).param(eq("tenant"), eq(tenant.value()));
    verify(statement).param(eq("employee"), eq(employee));
  }
}

package com.divalhr.core.payroll.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divalhr.core.platform.tenancy.TenantId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.MappedQuerySpec;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * MVP-095A: every payment destination, batch and line statement names the verified tenant, the
 * repository never deletes, and the only updates are the cancellation status of a PREPARED batch.
 */
class JdbcPaymentRepositoryTenantTest {

  private static final Path SOURCE =
      Path.of("src/main/java/com/divalhr/core/payroll/internal/JdbcPaymentRepository.java");
  private static final TenantId TENANT = TenantId.parse("11111111-1111-4111-8111-111111111111");
  private static final UUID EMPLOYEE = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID ENTITY = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID BATCH = UUID.fromString("44444444-4444-4444-8444-444444444444");

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

    JdbcPaymentRepository repository() {
      return new JdbcPaymentRepository(jdbc, mock(NamedParameterJdbcTemplate.class));
    }

    String sql() {
      ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
      verify(jdbc).sql(sql.capture());
      return sql.getValue();
    }
  }

  @Test
  void theLatestDestinationIsReadInsideTheVerifiedTenant() {
    Fixture fixture = new Fixture();
    assertThat(fixture.repository().latest(TENANT, EMPLOYEE)).isEmpty();
    assertThat(fixture.sql()).contains("d.tenant_id = :tenant").contains("d.employee_id");
    verify(fixture.statement).param(eq("tenant"), eq(TENANT.value()));
  }

  @Test
  void theEffectiveDestinationIsReadInsideTheVerifiedTenant() {
    Fixture fixture = new Fixture();
    assertThat(fixture.repository().effective(TENANT, EMPLOYEE, LocalDate.parse("2026-03-01")))
        .isEmpty();
    assertThat(fixture.sql()).contains("d.tenant_id = :tenant");
    verify(fixture.statement).param(eq("tenant"), eq(TENANT.value()));
  }

  @Test
  void aBatchIsReadInsideItsTenantAndLegalEntity() {
    Fixture fixture = new Fixture();
    assertThat(fixture.repository().batch(TENANT, ENTITY, BATCH)).isEmpty();
    assertThat(fixture.sql()).contains("b.tenant_id = :tenant").contains("b.legal_entity_id");
    verify(fixture.statement).param(eq("tenant"), eq(TENANT.value()));
  }

  @Test
  void everyTableReadFiltersOnTheTenantOfItsOwnAlias() throws IOException {
    String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
    for (String alias :
        List.of("payment_destination d", "payment_batch b", "payment_batch_line l")) {
      String prefix = alias.substring(alias.length() - 1);
      Pattern unguarded =
          Pattern.compile(
              "FROM payroll\\." + alias + " WHERE (?!" + prefix + "\\.tenant_id = :tenant)");
      assertThat(unguarded.matcher(source).find()).as(alias).isFalse();
      assertThat(source)
          .contains("FROM payroll." + alias + " WHERE " + prefix + ".tenant_id = :tenant");
    }
  }

  @Test
  void theRepositoryNeverDeletesAndOnlyUpdatesTheBatchStatus() throws IOException {
    String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
    assertThat(source).doesNotContainPattern("(?i)\"\\s*(DELETE FROM|TRUNCATE)");
    assertThat(source)
        .doesNotContainPattern(
            "(?i)UPDATE payroll\\.payment_(destination|batch_line|batch_cancel)");
    assertThat(source).contains("UPDATE payroll.payment_batch SET status = 'CANCELLED'");
    assertThat(source).contains("AND status = 'PREPARED'");
  }
}

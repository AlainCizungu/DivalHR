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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.MappedQuerySpec;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * MVP-095C (ADR 0038): every reconciliation and evidence statement that is not a worker claim names
 * the verified tenant, the repository never deletes, and the only update is the versioned
 * transition.
 */
class JdbcPaymentReconciliationRepositoryTenantTest {

  private static final Path SOURCE =
      Path.of(
          "src/main/java/com/divalhr/core/payroll/internal/"
              + "JdbcPaymentReconciliationRepository.java");
  private static final TenantId TENANT = TenantId.parse("11111111-1111-4111-8111-111111111111");
  private static final UUID SUBMISSION = UUID.fromString("44444444-4444-4444-8444-444444444444");

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static final class Fixture {
    final JdbcClient jdbc = mock(JdbcClient.class);
    final StatementSpec statement = mock(StatementSpec.class);
    final MappedQuerySpec query = mock(MappedQuerySpec.class);

    Fixture() {
      when(jdbc.sql(anyString())).thenReturn(statement);
      when(statement.param(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(statement);
      when(statement.query(org.mockito.ArgumentMatchers.any(RowMapper.class))).thenReturn(query);
      when(statement.query(Boolean.class)).thenReturn(query);
      when(query.optional()).thenReturn(Optional.empty());
      when(query.list()).thenReturn(List.of());
      when(query.single()).thenReturn(false);
    }

    JdbcPaymentReconciliationRepository repository() {
      return new JdbcPaymentReconciliationRepository(jdbc);
    }

    String sql() {
      ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
      verify(jdbc).sql(sql.capture());
      return sql.getValue();
    }
  }

  @Test
  void theReconciliationOfASubmissionIsReadInsideItsTenant() {
    Fixture fixture = new Fixture();
    assertThat(fixture.repository().bySubmission(TENANT, SUBMISSION)).isEmpty();
    assertThat(fixture.sql())
        .contains("r.tenant_id = :tenant")
        .contains("r.submission_id = :submission");
    verify(fixture.statement).param(eq("tenant"), eq(TENANT.value()));
  }

  @Test
  void theExistenceCheckIsInsideTheTenant() {
    Fixture fixture = new Fixture();
    assertThat(fixture.repository().existsForSubmission(TENANT, SUBMISSION)).isFalse();
    assertThat(fixture.sql())
        .contains("r.tenant_id = :tenant")
        .contains("r.submission_id = :submission");
    verify(fixture.statement).param(eq("tenant"), eq(TENANT.value()));
  }

  @Test
  void theEvidenceIsReadInsideItsTenant() {
    Fixture fixture = new Fixture();
    assertThat(fixture.repository().inquiryPage(TENANT, SUBMISSION, null, 10)).isEmpty();
    assertThat(fixture.sql())
        .contains("i.tenant_id = :tenant")
        .contains("i.reconciliation_id = :reconciliation");
    verify(fixture.statement).param(eq("tenant"), eq(TENANT.value()));

    Fixture one = new Fixture();
    assertThat(one.repository().inquiry(TENANT, SUBMISSION, 1)).isEmpty();
    assertThat(one.sql()).contains("i.tenant_id = :tenant").contains("i.seq = :seq");
  }

  @Test
  void everyTenantStatementNamesTheTenantAndOnlyTheWorkerClaimsAreCrossTenant() throws IOException {
    String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
    Pattern unguarded =
        Pattern.compile(
            "FROM payroll\\.payment_reconciliation r WHERE\\s*\"?\\s*(?!\\s*\"?\\s*r\\.tenant_id = :tenant)");
    // The cross-tenant statements are exactly the worker's claim and recovery and the gauge counts.
    long crossTenant = unguarded.matcher(source).results().count();
    assertThat(crossTenant).isLessThanOrEqualTo(4);
    assertThat(Pattern.compile("@CrossTenantAccess").matcher(source).results().count())
        .isEqualTo(3);
    assertThat(source)
        .containsPattern(
            "r\\.tenant_id = :tenant\"\\s*\\+\\s*\" AND r\\.submission_id = :submission\"");
    assertThat(source).contains("WHERE tenant_id = :tenant AND id = :id AND version = :expected");
    assertThat(source)
        .containsPattern("r\\.tenant_id = :tenant\"\\s*\\+\\s*\" AND r\\.id = :id FOR UPDATE");
  }

  @Test
  void theRepositoryNeverDeletesAndOnlyTheVersionedTransitionUpdates() throws IOException {
    String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
    assertThat(source).doesNotContainPattern("(?i)\"\\s*(DELETE FROM|TRUNCATE)");
    assertThat(source)
        .doesNotContainPattern("(?i)UPDATE payroll\\.payment_submission")
        .doesNotContainPattern("(?i)UPDATE payroll\\.payment_reconciliation_inquiry")
        .containsOnlyOnce("UPDATE payroll.payment_reconciliation SET state = :state");
  }
}

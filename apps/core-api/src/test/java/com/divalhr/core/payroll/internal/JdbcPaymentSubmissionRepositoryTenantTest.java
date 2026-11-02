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
 * MVP-095B (ADR 0037): every submission and evidence statement that is not a worker claim names the
 * verified tenant, the repository never deletes, and the only update is the versioned transition.
 */
class JdbcPaymentSubmissionRepositoryTenantTest {

  private static final Path SOURCE =
      Path.of("src/main/java/com/divalhr/core/payroll/internal/JdbcPaymentSubmissionRepository.java");
  private static final TenantId TENANT = TenantId.parse("11111111-1111-4111-8111-111111111111");
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
      when(statement.query(Boolean.class)).thenReturn(query);
      when(query.optional()).thenReturn(Optional.empty());
      when(query.list()).thenReturn(List.of());
      when(query.single()).thenReturn(false);
    }

    JdbcPaymentSubmissionRepository repository() {
      return new JdbcPaymentSubmissionRepository(jdbc);
    }

    String sql() {
      ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
      verify(jdbc).sql(sql.capture());
      return sql.getValue();
    }
  }

  @Test
  void theSubmissionOfABatchIsReadInsideItsTenantAndLegalEntity() {
    Fixture fixture = new Fixture();
    assertThat(fixture.repository().byBatch(TENANT, ENTITY, BATCH)).isEmpty();
    assertThat(fixture.sql())
        .contains("s.tenant_id = :tenant")
        .contains("s.legal_entity_id = :entity")
        .contains("s.batch_id = :batch");
    verify(fixture.statement).param(eq("tenant"), eq(TENANT.value()));
  }

  @Test
  void theExistenceCheckIsInsideTheTenant() {
    Fixture fixture = new Fixture();
    assertThat(fixture.repository().existsForBatch(TENANT, BATCH)).isFalse();
    assertThat(fixture.sql()).contains("s.tenant_id = :tenant").contains("s.batch_id = :batch");
    verify(fixture.statement).param(eq("tenant"), eq(TENANT.value()));
  }

  @Test
  void everyTenantStatementNamesTheTenantAndOnlyTheWorkerClaimsAreCrossTenant() throws IOException {
    String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
    Pattern unguarded =
        Pattern.compile(
            "FROM payroll\\.payment_submission s WHERE\\s*\"?\\s*(?!\\s*\"?\\s*s\\.tenant_id = :tenant)");
    // The three cross-tenant statements are exactly the worker's claim, recovery and the gauge.
    long crossTenant = unguarded.matcher(source).results().count();
    assertThat(crossTenant).isLessThanOrEqualTo(4);
    assertThat(Pattern.compile("@CrossTenantAccess").matcher(source).results().count()).isEqualTo(3);
    for (String name : List.of("lockDue", "lockExpired", "openCount")) {
      int at = source.indexOf("public " + (name.equals("openCount") ? "int " : "List<SubmissionRow> ") + name);
      assertThat(at).as(name).isGreaterThan(0);
      assertThat(source.lastIndexOf("@CrossTenantAccess", at)).as(name).isGreaterThan(at - 400);
    }
    assertThat(source).contains("WHERE a.tenant_id = :tenant AND a.submission_id = :submission");
    assertThat(source).contains("WHERE tenant_id = :tenant AND id = :id AND version = :expected");
    assertThat(source).contains("s.tenant_id = :tenant AND s.id = :id FOR UPDATE");
  }

  @Test
  void theRepositoryNeverDeletesAndOnlyTheVersionedTransitionUpdates() throws IOException {
    String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
    assertThat(source).doesNotContainPattern("(?i)\"\\s*(DELETE FROM|TRUNCATE)");
    assertThat(source)
        .doesNotContainPattern("(?i)UPDATE payroll\\.payment_submission_attempt")
        .containsOnlyOnce("UPDATE payroll.payment_submission SET state = :state");
  }
}

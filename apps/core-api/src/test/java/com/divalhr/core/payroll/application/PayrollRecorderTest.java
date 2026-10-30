package com.divalhr.core.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.divalhr.core.payroll.application.PayrollRecorder.PolicyRecorded;
import com.divalhr.core.payroll.application.PayrollRecorder.RunRecorded;
import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Audit and event payloads of payroll policies and runs are an allow-list without amounts. */
class PayrollRecorderTest {

  private static final PayrollCaller CALLER =
      new PayrollCaller(
          TenantId.parse("11111111-1111-4111-8111-111111111111"), "subject-1", "corr-1");
  private static final Instant AT = Instant.parse("2026-10-29T10:00:00Z");
  private static final Set<String> ALLOWED =
      Set.of(
          "legalEntityId",
          "runId",
          "version",
          "period",
          "currency",
          "status",
          "policySequence",
          "employeeCount",
          "blockedCount",
          "supersededRunId",
          "view",
          "page",
          "resultCount",
          "schemaVersion");

  private final AuditRecorder audit = mock(AuditRecorder.class);
  private final OutboxWriter outbox = mock(OutboxWriter.class);
  private final PayrollRecorder recorder = new PayrollRecorder(audit, outbox);

  @Test
  void aCreatedRunWritesOneAuditRecordAndOneInternalEventFromTheAllowList() {
    UUID superseded = UUID.randomUUID();
    String digest = "d".repeat(64);
    recorder.runCreated(
        CALLER,
        new RunRecorded(
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            "2026-05",
            CompensationCurrency.USD,
            3,
            12,
            superseded,
            digest,
            AT));
    ArgumentCaptor<AuditEvent> auditEvent = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).record(auditEvent.capture());
    ArgumentCaptor<EventEnvelope> envelope = ArgumentCaptor.forClass(EventEnvelope.class);
    verify(outbox).append(envelope.capture());

    assertThat(auditEvent.getValue().action()).isEqualTo("payroll-run.create");
    assertThat(auditEvent.getValue().resourceType()).isEqualTo("payroll-run");
    assertThat(auditEvent.getValue().metadata().keySet()).isSubsetOf(ALLOWED);
    assertThat(auditEvent.getValue().metadata())
        .containsEntry("period", "2026-05")
        .containsEntry("currency", "USD")
        .containsEntry("status", "DRAFT")
        .containsEntry("employeeCount", 12)
        .containsEntry("supersededRunId", superseded.toString());
    // The digest of the inputs is hashed into the integrity reference, never copied.
    assertThat(auditEvent.getValue().afterStateSha256()).matches("[0-9a-f]{64}").isNotEqualTo(digest);
    assertThat(auditEvent.getValue().metadata().toString()).doesNotContain(digest);
    assertThat(envelope.getValue().eventType()).isEqualTo("payroll.payroll-run.created.v1");
    assertThat(envelope.getValue().source()).isEqualTo("core-api/payroll");
    assertThat(envelope.getValue().data().keySet()).isSubsetOf(ALLOWED);
    assertThat(envelope.getValue().data().toString())
        .doesNotContainIgnoringCase("amount")
        .doesNotContainIgnoringCase("gross")
        .doesNotContainIgnoringCase("minor")
        .doesNotContainIgnoringCase("digest")
        .doesNotContainIgnoringCase("explanation")
        .doesNotContainIgnoringCase("number");
  }

  @Test
  void aRecordedPolicyWritesOneAuditRecordAndOneInternalEvent() {
    UUID entity = UUID.randomUUID();
    recorder.policyChanged(CALLER, new PolicyRecorded(entity, UUID.randomUUID(), 4, AT));
    ArgumentCaptor<AuditEvent> auditEvent = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).record(auditEvent.capture());
    ArgumentCaptor<EventEnvelope> envelope = ArgumentCaptor.forClass(EventEnvelope.class);
    verify(outbox).append(envelope.capture());
    assertThat(auditEvent.getValue().action()).isEqualTo("payroll-policy.change");
    assertThat(auditEvent.getValue().metadata().keySet()).isSubsetOf(ALLOWED);
    assertThat(auditEvent.getValue().metadata()).containsEntry("legalEntityId", entity.toString());
    assertThat(envelope.getValue().eventType()).isEqualTo("payroll.payroll-policy.changed.v1");
    assertThat(envelope.getValue().data().keySet()).isSubsetOf(ALLOWED);
  }

  @Test
  void aDisclosureCarriesOnlyTheViewAndCounts() {
    UUID entity = UUID.randomUUID();
    recorder.disclosed(
        CALLER, "payroll-run.read", "payroll-run", entity, entity, "list", false, 3, AT);
    ArgumentCaptor<AuditEvent> auditEvent = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).record(auditEvent.capture());
    assertThat(auditEvent.getValue().metadata().keySet())
        .containsExactlyInAnyOrder("schemaVersion", "legalEntityId", "view", "page", "resultCount");
    assertThat(auditEvent.getValue().metadata()).containsEntry("page", "cursor");
    assertThat(auditEvent.getValue().resourceId()).isEqualTo(entity);
    assertThat(auditEvent.getValue().afterStateSha256()).matches("[0-9a-f]{64}");
  }
}

package com.divalhr.core.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.divalhr.core.payroll.application.CompensationRecorder.Recorded;
import com.divalhr.core.payroll.domain.CompensationAction;
import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Audit and event payloads of the compensation commands are an allow-list without amounts. */
class CompensationRecorderTest {

  private static final PayrollCaller CALLER =
      new PayrollCaller(
          TenantId.parse("11111111-1111-4111-8111-111111111111"), "subject-1", "corr-1");
  private static final Set<String> ALLOWED =
      Set.of(
          "schemaVersion",
          "employeeId",
          "changeId",
          "sequence",
          "action",
          "currency",
          "frequency",
          "effectiveFrom",
          "version",
          "reasonSupplied",
          "retroactive",
          "supersededFacts",
          "createdFacts",
          "targetChangeId");

  private final AuditRecorder audit = mock(AuditRecorder.class);
  private final OutboxWriter outbox = mock(OutboxWriter.class);
  private final CompensationRecorder recorder = new CompensationRecorder(audit, outbox);

  private static Recorded recorded(CompensationAction action, UUID target) {
    return new Recorded(
        UUID.randomUUID(),
        UUID.randomUUID(),
        4,
        action,
        CompensationCurrency.USD,
        LocalDate.parse("2026-11-01"),
        target,
        target != null,
        false,
        1,
        2,
        "a".repeat(64),
        Instant.parse("2026-10-29T10:00:00Z"));
  }

  @Test
  void aRecordedChangeWritesOneAuditRecordAndOneInternalEventFromTheAllowList() {
    Recorded recorded = recorded(CompensationAction.CORRECTED, UUID.randomUUID());
    recorder.recorded(CALLER, recorded);
    ArgumentCaptor<AuditEvent> auditEvent = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).record(auditEvent.capture());
    ArgumentCaptor<EventEnvelope> envelope = ArgumentCaptor.forClass(EventEnvelope.class);
    verify(outbox).append(envelope.capture());

    assertThat(auditEvent.getValue().action()).isEqualTo("compensation.correct");
    assertThat(auditEvent.getValue().afterStateSha256()).isEqualTo("a".repeat(64));
    assertThat(auditEvent.getValue().metadata().keySet()).isSubsetOf(ALLOWED);
    assertThat(envelope.getValue().eventType()).isEqualTo("payroll.compensation.corrected.v1");
    Set<String> eventKeys = new HashSet<>(ALLOWED);
    eventKeys.add("occurredAt");
    assertThat(envelope.getValue().data().keySet()).isSubsetOf(eventKeys);
    assertThat(auditEvent.getValue().metadata().toString())
        .doesNotContainIgnoringCase("amount")
        .doesNotContainIgnoringCase("explanation")
        .doesNotContainIgnoringCase("minor");
  }

  @Test
  void aDisclosureCarriesOnlyTheViewAndCounts() {
    UUID employee = UUID.randomUUID();
    recorder.disclosed(
        CALLER,
        "compensation.read",
        employee,
        "administrator",
        true,
        3,
        Instant.parse("2026-10-29T10:00:00Z"));
    ArgumentCaptor<AuditEvent> auditEvent = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).record(auditEvent.capture());
    assertThat(auditEvent.getValue().metadata().keySet())
        .containsExactlyInAnyOrder("schemaVersion", "view", "page", "resultCount");
    assertThat(auditEvent.getValue().resourceId()).isEqualTo(employee);
    assertThat(auditEvent.getValue().afterStateSha256()).matches("[0-9a-f]{64}");
  }
}

package com.divalhr.core.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.divalhr.core.payroll.application.PaymentReconciliationRecorder.Change;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * MVP-095C: audit metadata and event data are an allow-list of identifiers, state, counts and the
 * outcome code; no amount, destination, reference, signature or raw request ID can be added.
 */
class PaymentReconciliationRecorderTest {

  private static final TenantId TENANT = TenantId.parse("11111111-1111-4111-8111-111111111111");
  private static final PayrollCaller CALLER = new PayrollCaller(TENANT, "subject-1", "corr-1");
  private static final Instant AT = Instant.parse("2026-11-02T10:00:00Z");

  private final AuditRecorder audit = mock(AuditRecorder.class);
  private final OutboxWriter outbox = mock(OutboxWriter.class);
  private final PaymentReconciliationRecorder recorder =
      new PaymentReconciliationRecorder(audit, outbox);

  private static Change change(String state, String outcome, int attempt) {
    return new Change(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "SYNTHETIC-TEST",
        state,
        attempt + 1,
        attempt,
        outcome,
        AT);
  }

  @Test
  void anOpenedInvestigationWritesIdentifiersStateAndCountsOnly() {
    Change created = change("OPEN", null, 0);
    recorder.created(CALLER, created);
    ArgumentCaptor<AuditEvent> auditEvent = ArgumentCaptor.forClass(AuditEvent.class);
    ArgumentCaptor<EventEnvelope> envelope = ArgumentCaptor.forClass(EventEnvelope.class);
    verify(audit).record(auditEvent.capture());
    verify(outbox).append(envelope.capture());
    assertThat(auditEvent.getValue().action()).isEqualTo("payment-reconciliation.create");
    assertThat(auditEvent.getValue().metadata().keySet())
        .isSubsetOf(PaymentReconciliationRecorder.ALLOWED);
    assertThat(auditEvent.getValue().metadata())
        .containsEntry("state", "OPEN")
        .containsEntry("attemptNumber", 0)
        .containsEntry("providerCode", "SYNTHETIC-TEST")
        .doesNotContainKey("outcomeCode");
    assertThat(envelope.getValue().eventType())
        .isEqualTo("payroll.payment-reconciliation.created.v1");
    assertThat(envelope.getValue().data().keySet())
        .containsExactlyInAnyOrder(
            "schemaVersion", "reconciliationId", "submissionId", "batchId", "state", "version");
  }

  @Test
  void aWorkerStateChangeCarriesTheOutcomeCodeOnlyAndIsAttributedToTheWorker() {
    recorder.inquired(TENANT, "corr-w", change("OPEN", "TRANSIENT_TIMEOUT", 1));
    recorder.inquired(
        TENANT,
        "corr-w",
        change("CONFIRMED_ACCEPTED_FOR_PROCESSING", "ACCEPTED_FOR_PROCESSING", 2));
    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    ArgumentCaptor<EventEnvelope> envelopes = ArgumentCaptor.forClass(EventEnvelope.class);
    verify(audit, times(2)).record(events.capture());
    verify(outbox, times(2)).append(envelopes.capture());
    for (AuditEvent event : events.getAllValues()) {
      assertThat(event.action()).isEqualTo("payment-reconciliation.inquiry");
      assertThat(event.actorSubject()).isEqualTo(PaymentReconciliationRecorder.WORKER_ACTOR);
      assertThat(event.metadata().keySet()).isSubsetOf(PaymentReconciliationRecorder.ALLOWED);
      assertThat(event.metadata()).containsKey("outcomeCode");
    }
    for (EventEnvelope envelope : envelopes.getAllValues()) {
      assertThat(envelope.eventType()).isEqualTo("payroll.payment-reconciliation.state-changed.v1");
      assertThat(envelope.data().keySet()).isSubsetOf(PaymentReconciliationRecorder.ALLOWED);
      assertThat(envelope.data()).containsKey("outcomeCode");
      assertThat(envelope.data().toString()).doesNotContainIgnoringCase("paid");
    }
  }

  @Test
  void neitherMetadataNorEventDataCarryAnAmountDestinationReferenceSignatureOrRawRequestId() {
    Change change = change("OPEN", "TRANSIENT_SERVER", 1);
    recorder.created(CALLER, change);
    recorder.inquired(TENANT, "corr-w", change);
    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    ArgumentCaptor<EventEnvelope> envelopes = ArgumentCaptor.forClass(EventEnvelope.class);
    verify(audit, times(2)).record(events.capture());
    verify(outbox, times(2)).append(envelopes.capture());
    for (AuditEvent event : events.getAllValues()) {
      assertThat(event.metadata().keySet()).noneMatch(PaymentReconciliationRecorderTest::sensitive);
      assertThat(event.metadata().toString()).doesNotContain("v1=");
    }
    for (EventEnvelope envelope : envelopes.getAllValues()) {
      assertThat(envelope.data().keySet()).noneMatch(PaymentReconciliationRecorderTest::sensitive);
      assertThat(envelope.data().toString()).doesNotContain("v1=");
    }
    assertThat(change.toString())
        .contains(change.reconciliationId().toString())
        .doesNotContain("SYNTHETIC-TEST");
  }

  @Test
  void aDisclosureCarriesTheViewAndCountsOnly() {
    UUID submission = UUID.randomUUID();
    recorder.disclosed(
        CALLER,
        PaymentReconciliationRecorder.READ,
        submission,
        Map.of("submissionId", submission.toString()),
        "status",
        true,
        1,
        AT);
    recorder.disclosed(
        CALLER,
        PaymentReconciliationRecorder.READ,
        submission,
        Map.of("reconciliationId", submission.toString()),
        "inquiries",
        false,
        5,
        AT);
    ArgumentCaptor<AuditEvent> auditEvent = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, times(2)).record(auditEvent.capture());
    assertThat(auditEvent.getAllValues().get(0).metadata())
        .containsEntry("view", "status")
        .containsEntry("page", "first")
        .containsEntry("resultCount", 1);
    assertThat(auditEvent.getAllValues().get(1).metadata())
        .containsEntry("view", "inquiries")
        .containsEntry("page", "cursor")
        .containsEntry("resultCount", 5);
    for (AuditEvent event : auditEvent.getAllValues()) {
      assertThat(event.metadata().keySet()).isSubsetOf(PaymentReconciliationRecorder.ALLOWED);
    }
  }

  @Test
  void aKeyOutsideTheAllowListIsRefusedBeforeAnythingIsWritten() {
    UUID submission = UUID.randomUUID();
    for (String key : new String[] {"accountNumber", "amountMinor", "requestId", "signature"}) {
      assertThatThrownBy(
              () ->
                  recorder.disclosed(
                      CALLER,
                      PaymentReconciliationRecorder.PREVIEW,
                      submission,
                      Map.of(key, "SYNTHETIC"),
                      "preview",
                      true,
                      1,
                      AT))
          .as(key)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining("SYNTHETIC");
    }
    verifyNoInteractions(audit, outbox);
  }

  @Test
  void theAllowListNamesNoAmountReferenceDestinationSignatureOrRequestIdKey() {
    assertThat(PaymentReconciliationRecorder.ALLOWED)
        .noneMatch(PaymentReconciliationRecorderTest::sensitive);
    assertThat(PaymentReconciliationRecorder.WORKER_ACTOR).startsWith("system:");
  }

  private static boolean sensitive(String key) {
    String lower = key.toLowerCase();
    return lower.contains("amount")
        || lower.contains("total")
        || lower.contains("reference")
        || lower.contains("account")
        || lower.contains("destination")
        || lower.contains("signature")
        || lower.contains("digest")
        || lower.equals("requestid")
        || lower.contains("employee");
  }
}

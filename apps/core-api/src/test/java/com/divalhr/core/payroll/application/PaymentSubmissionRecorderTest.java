package com.divalhr.core.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.divalhr.core.payroll.application.PaymentSubmissionRecorder.Change;
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
 * MVP-095B: audit metadata and event data are an allow-list of identifiers, state, counts and the
 * outcome code; no amount, reference, destination or signature can be added.
 */
class PaymentSubmissionRecorderTest {

  private static final TenantId TENANT = TenantId.parse("11111111-1111-4111-8111-111111111111");
  private static final PayrollCaller CALLER = new PayrollCaller(TENANT, "subject-1", "corr-1");
  private static final Instant AT = Instant.parse("2026-11-02T10:00:00Z");

  private final AuditRecorder audit = mock(AuditRecorder.class);
  private final OutboxWriter outbox = mock(OutboxWriter.class);
  private final PaymentSubmissionRecorder recorder = new PaymentSubmissionRecorder(audit, outbox);

  private static Change change(String state, String outcome, int attempt) {
    return new Change(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "SYNTHETIC-TEST",
        state,
        attempt + 1,
        3,
        attempt,
        outcome,
        "d".repeat(64),
        AT);
  }

  @Test
  void aCreatedSubmissionWritesIdentifiersStateAndCountsOnly() {
    Change created = change("QUEUED", null, 0);
    recorder.created(CALLER, created);
    ArgumentCaptor<AuditEvent> auditEvent = ArgumentCaptor.forClass(AuditEvent.class);
    ArgumentCaptor<EventEnvelope> envelope = ArgumentCaptor.forClass(EventEnvelope.class);
    verify(audit).record(auditEvent.capture());
    verify(outbox).append(envelope.capture());
    assertThat(auditEvent.getValue().action()).isEqualTo("payment-submission.create");
    assertThat(auditEvent.getValue().metadata().keySet())
        .isSubsetOf(PaymentSubmissionRecorder.ALLOWED);
    assertThat(auditEvent.getValue().metadata())
        .containsEntry("state", "QUEUED")
        .containsEntry("lineCount", 3)
        .containsEntry("providerCode", "SYNTHETIC-TEST");
    assertThat(envelope.getValue().eventType()).isEqualTo("payroll.payment-submission.created.v1");
    assertThat(envelope.getValue().data().keySet())
        .containsExactlyInAnyOrder("schemaVersion", "submissionId", "batchId", "state", "version");
    assertThat(auditEvent.getValue().metadata().toString()).doesNotContain("d".repeat(64));
  }

  @Test
  void aWorkerStateChangeCarriesTheOutcomeCodeOnlyAndIsAttributedToTheWorker() {
    recorder.attempted(TENANT, "corr-w", change("DELIVERY_UNKNOWN", "UNKNOWN_TIMEOUT", 1));
    recorder.attempted(TENANT, "corr-w", change("ACCEPTED_FOR_PROCESSING", "ACCEPTED", 2));
    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    ArgumentCaptor<EventEnvelope> envelopes = ArgumentCaptor.forClass(EventEnvelope.class);
    verify(audit, times(2)).record(events.capture());
    verify(outbox, times(2)).append(envelopes.capture());
    for (AuditEvent event : events.getAllValues()) {
      assertThat(event.action()).isEqualTo("payment-submission.attempt");
      assertThat(event.actorSubject()).isEqualTo(PaymentSubmissionRecorder.WORKER_ACTOR);
      assertThat(event.metadata().keySet()).isSubsetOf(PaymentSubmissionRecorder.ALLOWED);
      assertThat(event.metadata()).containsKey("outcomeCode");
    }
    for (EventEnvelope envelope : envelopes.getAllValues()) {
      assertThat(envelope.eventType()).isEqualTo("payroll.payment-submission.state-changed.v1");
      assertThat(envelope.data().keySet()).isSubsetOf(PaymentSubmissionRecorder.ALLOWED);
      assertThat(envelope.data().toString()).doesNotContainIgnoringCase("paid");
    }
  }

  @Test
  void aDisclosureCarriesTheViewAndCountsOnly() {
    UUID batch = UUID.randomUUID();
    recorder.disclosed(
        CALLER,
        PaymentSubmissionRecorder.READ,
        batch,
        Map.of("batchId", batch.toString()),
        "status",
        true,
        1,
        AT);
    ArgumentCaptor<AuditEvent> auditEvent = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).record(auditEvent.capture());
    assertThat(auditEvent.getValue().metadata().keySet())
        .isSubsetOf(PaymentSubmissionRecorder.ALLOWED);
    assertThat(auditEvent.getValue().metadata())
        .containsEntry("view", "status")
        .containsEntry("resultCount", 1);
  }

  @Test
  void aKeyOutsideTheAllowListIsRefusedBeforeAnythingIsWritten() {
    UUID batch = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                recorder.disclosed(
                    CALLER,
                    PaymentSubmissionRecorder.PREVIEW,
                    batch,
                    Map.of("accountNumber", "SYNTHETIC"),
                    "preview",
                    true,
                    1,
                    AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("SYNTHETIC");
    verifyNoInteractions(audit, outbox);
  }

  @Test
  void theAllowListNamesNoAmountReferenceDestinationOrSignatureKey() {
    assertThat(PaymentSubmissionRecorder.ALLOWED)
        .noneMatch(
            key ->
                key.toLowerCase().contains("amount")
                    || key.toLowerCase().contains("total")
                    || key.toLowerCase().contains("reference")
                    || key.toLowerCase().contains("account")
                    || key.toLowerCase().contains("signature")
                    || key.toLowerCase().contains("employee"));
  }
}

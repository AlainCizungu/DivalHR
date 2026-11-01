package com.divalhr.core.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.divalhr.core.payroll.application.PaymentRecorder.BatchRecorded;
import com.divalhr.core.payroll.application.PaymentRecorder.DestinationRecorded;
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

/** MVP-095A: audit metadata and event data are an allow-list without any sensitive value. */
class PaymentRecorderTest {

  private static final PayrollCaller CALLER =
      new PayrollCaller(
          TenantId.parse("11111111-1111-4111-8111-111111111111"), "subject-1", "corr-1");
  private static final Instant AT = Instant.parse("2026-10-29T10:00:00Z");

  private final AuditRecorder audit = mock(AuditRecorder.class);
  private final OutboxWriter outbox = mock(OutboxWriter.class);
  private final PaymentRecorder recorder = new PaymentRecorder(audit, outbox);

  @Test
  void aRecordedDestinationWritesIdentifiersAndTheMethodOnly() {
    UUID employee = UUID.randomUUID();
    recorder.destinationRecorded(CALLER, new DestinationRecorded(employee, 2, "BANK_ACCOUNT", AT));
    ArgumentCaptor<AuditEvent> auditEvent = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).record(auditEvent.capture());
    ArgumentCaptor<EventEnvelope> envelope = ArgumentCaptor.forClass(EventEnvelope.class);
    verify(outbox).append(envelope.capture());
    assertThat(auditEvent.getValue().action()).isEqualTo("payment-destination.record");
    assertThat(auditEvent.getValue().metadata().keySet()).isSubsetOf(PaymentRecorder.ALLOWED);
    assertThat(auditEvent.getValue().metadata())
        .containsEntry("employeeId", employee.toString())
        .containsEntry("version", 2)
        .containsEntry("method", "BANK_ACCOUNT")
        .containsEntry("status", "RECORDED");
    assertThat(envelope.getValue().eventType()).isEqualTo("payroll.payment-destination.recorded.v1");
    assertThat(envelope.getValue().data()).isEqualTo(auditEvent.getValue().metadata());
  }

  @Test
  void aPreparedAndACancelledBatchCarryCountsAndStatusOnly() {
    String digest = "d".repeat(64);
    BatchRecorded batch =
        new BatchRecorded(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "2026-10", 7, digest, AT);
    recorder.prepared(CALLER, batch);
    recorder.cancelled(CALLER, batch);
    ArgumentCaptor<AuditEvent> auditEvents = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, org.mockito.Mockito.times(2)).record(auditEvents.capture());
    ArgumentCaptor<EventEnvelope> envelopes = ArgumentCaptor.forClass(EventEnvelope.class);
    verify(outbox, org.mockito.Mockito.times(2)).append(envelopes.capture());
    assertThat(auditEvents.getAllValues().get(0).action()).isEqualTo("payment-batch.prepare");
    assertThat(auditEvents.getAllValues().get(1).action()).isEqualTo("payment-batch.cancel");
    assertThat(envelopes.getAllValues().get(0).eventType())
        .isEqualTo("payroll.payment-batch.prepared.v1");
    assertThat(envelopes.getAllValues().get(1).eventType())
        .isEqualTo("payroll.payment-batch.cancelled.v1");
    for (AuditEvent event : auditEvents.getAllValues()) {
      assertThat(event.metadata().keySet()).isSubsetOf(PaymentRecorder.ALLOWED);
      assertThat(event.metadata().toString()).doesNotContain(digest);
      assertThat(event.metadata()).containsEntry("lineCount", 7).containsEntry("currency", "CDF");
      assertThat(event.afterStateSha256()).isNotEqualTo(digest);
    }
  }

  @Test
  void aDisclosureCarriesTheViewAndCountsOnly() {
    UUID batch = UUID.randomUUID();
    recorder.disclosed(
        CALLER,
        PaymentRecorder.REGISTER_DOWNLOAD,
        "payment-batch",
        batch,
        Map.of("batchId", batch.toString()),
        "csv",
        true,
        5,
        AT);
    ArgumentCaptor<AuditEvent> auditEvent = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).record(auditEvent.capture());
    assertThat(auditEvent.getValue().metadata().keySet()).isSubsetOf(PaymentRecorder.ALLOWED);
    assertThat(auditEvent.getValue().metadata())
        .containsEntry("view", "csv")
        .containsEntry("resultCount", 5);
  }

  @Test
  void aKeyOutsideTheAllowListIsRefusedBeforeAnythingIsWritten() {
    UUID employee = UUID.randomUUID();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                recorder.disclosed(
                    CALLER,
                    PaymentRecorder.DESTINATION_READ,
                    "payment-destination",
                    employee,
                    Map.of("accountNumber", "SYNTHETIC"),
                    "current",
                    true,
                    1,
                    AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("SYNTHETIC");
    org.mockito.Mockito.verifyNoInteractions(audit, outbox);
  }
}

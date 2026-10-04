package com.divalhr.core.documents.application;

import com.divalhr.core.documents.domain.ContractDigests;
import com.divalhr.core.documents.internal.JdbcContractRepository;
import com.divalhr.core.documents.internal.JdbcContractRepository.IntegrityRow;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Read-only reconciliation of issued contracts (MVP-030, §7): recomputes every snapshot digest from
 * its stored canonical text and checks that the structured {@code jsonb} equals that text. A
 * mismatch increments {@code divalhr.contract.integrity{outcome="mismatch"}} (alert), is logged
 * with the contract ID only and recorded as a FAILURE audit event in the contract's tenant. It
 * never repairs anything.
 */
@Component
public class ContractIntegrityJob {

  /** Actor of the job's audit records. */
  static final String ACTOR = "system:contract-integrity";

  /** Counter of checked contracts by outcome ({@code ok} or {@code mismatch}). */
  public static final String METRIC = "divalhr.contract.integrity";

  private static final Logger LOG = LoggerFactory.getLogger(ContractIntegrityJob.class);

  private final JdbcContractRepository contracts;
  private final ContractEvents events;
  private final ContractCalendar calendar;
  private final ContractProperties properties;
  private final TransactionTemplate transactions;
  private final MeterRegistry meters;

  /**
   * Creates the job.
   *
   * @param contracts contract repository
   * @param events audit
   * @param calendar clock
   * @param properties settings
   * @param transactionManager transaction manager
   * @param meters meter registry
   */
  public ContractIntegrityJob(
      JdbcContractRepository contracts,
      ContractEvents events,
      ContractCalendar calendar,
      ContractProperties properties,
      PlatformTransactionManager transactionManager,
      MeterRegistry meters) {
    this.contracts = contracts;
    this.events = events;
    this.calendar = calendar;
    this.properties = properties;
    this.transactions = new TransactionTemplate(transactionManager);
    this.meters = meters;
  }

  /**
   * The outcome of one run.
   *
   * @param checked contracts checked
   * @param mismatches contracts whose stored digest or structure does not match
   */
  public record Run(int checked, int mismatches) {}

  /**
   * Checks every contract, in batches by ID.
   *
   * @return the counts
   */
  public Run run() {
    String correlationId = "contract-integrity-" + UUID.randomUUID();
    int checked = 0;
    int mismatches = 0;
    UUID after = null;
    while (true) {
      List<IntegrityRow> batch = contracts.integrityBatch(after, properties.integrityBatchSize());
      if (batch.isEmpty()) {
        break;
      }
      for (IntegrityRow row : batch) {
        checked++;
        boolean ok =
            row.structuredMatches()
                && ContractDigests.equal(
                    ContractDigests.snapshot(row.canonical()), row.snapshotSha256());
        count(ok ? "ok" : "mismatch");
        if (!ok) {
          mismatches++;
          LOG.atError()
              .addKeyValue("contractId", row.id())
              .addKeyValue("outcome", "mismatch")
              .log("contract_integrity_mismatch");
          transactions.executeWithoutResult(
              status ->
                  events.integrityMismatch(
                      new TenantId(row.tenantId()), row.id(), correlationId, calendar.now()));
        }
      }
      after = batch.get(batch.size() - 1).id();
    }
    LOG.atInfo()
        .addKeyValue("checked", checked)
        .addKeyValue("mismatches", mismatches)
        .log("contract_integrity_checked");
    return new Run(checked, mismatches);
  }

  private void count(String outcome) {
    Counter.builder(METRIC)
        .description("Issued contracts checked by the integrity job, by outcome")
        .tag("outcome", outcome)
        .register(meters)
        .increment();
  }
}

package com.divalhr.core.payroll.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the payment reconciliation worker on a fixed delay (MVP-095C); governed by the same switch
 * and interval as the submission worker, and disabled in integration tests.
 */
@Configuration
@ConditionalOnProperty(
    name = "divalhr.payroll.submission.worker.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PaymentReconciliationScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentReconciliationScheduler.class);

  private final PaymentReconciliationWorker worker;

  /**
   * Creates the scheduler.
   *
   * @param worker the worker
   */
  public PaymentReconciliationScheduler(PaymentReconciliationWorker worker) {
    this.worker = worker;
  }

  /** One tick; a failure is logged by class only and never stops the schedule. */
  @Scheduled(fixedDelayString = "${divalhr.payroll.submission.worker.interval:PT5S}")
  void tick() {
    try {
      worker.runOnce();
    } catch (RuntimeException failure) {
      LOG.atWarn()
          .addKeyValue("operation", "payment-reconciliation.worker")
          .addKeyValue("error", failure.getClass().getSimpleName())
          .log("payment_reconciliation_worker_failed");
    }
  }
}

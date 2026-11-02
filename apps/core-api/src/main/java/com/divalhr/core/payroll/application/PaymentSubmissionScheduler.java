package com.divalhr.core.payroll.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the provider submission worker on a fixed delay (MVP-095B); disabled in integration tests.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "divalhr.payroll.submission.worker.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PaymentSubmissionScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentSubmissionScheduler.class);

  private final PaymentSubmissionWorker worker;

  /**
   * Creates the scheduler.
   *
   * @param worker the worker
   */
  public PaymentSubmissionScheduler(PaymentSubmissionWorker worker) {
    this.worker = worker;
  }

  /** One tick; a failure is logged by class only and never stops the schedule. */
  @Scheduled(fixedDelayString = "${divalhr.payroll.submission.worker.interval:PT5S}")
  void tick() {
    try {
      worker.runOnce();
    } catch (RuntimeException failure) {
      LOG.atWarn()
          .addKeyValue("operation", "payment-submission.worker")
          .addKeyValue("error", failure.getClass().getSimpleName())
          .log("payment_submission_worker_failed");
    }
  }
}

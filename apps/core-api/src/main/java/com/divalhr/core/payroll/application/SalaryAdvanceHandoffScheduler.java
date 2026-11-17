package com.divalhr.core.payroll.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the partner delivery worker on a fixed delay (MVP-120B); disabled in integration tests by
 * {@code divalhr.payroll.partner.worker.enabled=false}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "divalhr.payroll.partner.worker.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SalaryAdvanceHandoffScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(SalaryAdvanceHandoffScheduler.class);

  private final SalaryAdvanceHandoffWorker worker;

  /**
   * Creates the scheduler.
   *
   * @param worker the worker
   */
  public SalaryAdvanceHandoffScheduler(SalaryAdvanceHandoffWorker worker) {
    this.worker = worker;
  }

  /** One tick; a failure is logged by class only and never stops the schedule. */
  @Scheduled(fixedDelayString = "${divalhr.payroll.partner.worker.interval:PT5S}")
  void tick() {
    try {
      worker.runOnce();
    } catch (RuntimeException failure) {
      LOG.atWarn()
          .addKeyValue("operation", "salary-advance-handoff.worker")
          .addKeyValue("error", failure.getClass().getSimpleName())
          .log("salary_advance_handoff_worker_failed");
    }
  }
}

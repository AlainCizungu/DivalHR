package com.divalhr.core.documents.internal;

import com.divalhr.core.documents.application.ContractIntegrityJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the read-only contract integrity job daily (MVP-030). Disabled with {@code
 * divalhr.contracts.jobs.enabled=false} (integration tests call the job directly). A failed run is
 * logged by exception type only and retried on the next run.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "divalhr.contracts.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ContractIntegrityJobScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(ContractIntegrityJobScheduler.class);

  private final ContractIntegrityJob job;

  /**
   * Creates the scheduler.
   *
   * @param job the integrity job
   */
  public ContractIntegrityJobScheduler(ContractIntegrityJob job) {
    this.job = job;
  }

  /** Daily (configurable): recompute and compare every stored snapshot digest. */
  @Scheduled(
      initialDelayString = "${divalhr.contracts.jobs.initial-delay:PT10M}",
      fixedDelayString = "${divalhr.contracts.jobs.interval:P1D}")
  public void integrity() {
    try {
      job.run();
    } catch (RuntimeException failed) {
      LOG.atWarn()
          .addKeyValue("job", "integrity")
          .addKeyValue("error", failed.getClass().getSimpleName())
          .log("contract_job_failed");
    }
  }
}

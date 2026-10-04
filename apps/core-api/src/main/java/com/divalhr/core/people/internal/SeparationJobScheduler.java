package com.divalhr.core.people.internal;

import com.divalhr.core.people.application.SeparationJobs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the separation effective-date job on a fixed delay (MVP-022). Disabled with {@code
 * divalhr.separation.jobs.enabled=false} (integration tests call the job directly). A failed run is
 * logged by exception type only and retried on the next run.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "divalhr.separation.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SeparationJobScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(SeparationJobScheduler.class);

  private final SeparationJobs jobs;

  /**
   * Creates the scheduler.
   *
   * @param jobs separation jobs
   */
  public SeparationJobScheduler(SeparationJobs jobs) {
    this.jobs = jobs;
  }

  /** Every minute: due separations become effective. */
  @Scheduled(fixedDelayString = "${divalhr.separation.jobs.interval:PT1M}")
  public void effective() {
    try {
      jobs.markEffective();
    } catch (RuntimeException failed) {
      LOG.atWarn()
          .addKeyValue("job", "effective")
          .addKeyValue("error", failed.getClass().getSimpleName())
          .log("separation_job_failed");
    }
  }
}

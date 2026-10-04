package com.divalhr.core.identity.internal;

import com.divalhr.core.identity.application.AccessRevocationJobs;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the access-revocation worker on a fixed delay (MVP-022). Disabled with {@code
 * divalhr.access-revocation.jobs.enabled=false} (integration tests call the jobs directly). A
 * failed run is logged by exception type only and retried on the next run. DivalHR access never
 * depends on it: the membership gate denies a revoked membership from its instant on.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "divalhr.access-revocation.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AccessRevocationJobScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(AccessRevocationJobScheduler.class);

  private final AccessRevocationJobs jobs;

  /**
   * Creates the scheduler.
   *
   * @param jobs revocation jobs
   */
  public AccessRevocationJobScheduler(AccessRevocationJobs jobs) {
    this.jobs = jobs;
  }

  /** Every minute: due revocations become effective, then pending ones reach the provider. */
  @Scheduled(fixedDelayString = "${divalhr.access-revocation.jobs.interval:PT1M}")
  public void run() {
    run("activate", jobs::activateDue);
    run("identity_provider", jobs::revokePending);
  }

  private static void run(String job, IntSupplier work) {
    try {
      work.getAsInt();
    } catch (RuntimeException failed) {
      LOG.atWarn()
          .addKeyValue("job", job)
          .addKeyValue("error", failed.getClass().getSimpleName())
          .log("access_revocation_job_failed");
    }
  }
}

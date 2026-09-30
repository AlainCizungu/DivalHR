package com.divalhr.core.identity.internal;

import com.divalhr.core.identity.application.InvitationJobs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the invitation jobs on a fixed delay (decision D-4). Disabled with {@code
 * divalhr.invitations.jobs.enabled=false} (integration tests call the jobs directly).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "divalhr.invitations.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class InvitationJobScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(InvitationJobScheduler.class);

  private final InvitationJobs jobs;

  /**
   * Creates the scheduler.
   *
   * @param jobs invitation jobs
   */
  public InvitationJobScheduler(InvitationJobs jobs) {
    this.jobs = jobs;
  }

  /** Every minute: acceptance recovery, credential-setup retries and stale deliveries. */
  @Scheduled(fixedDelayString = "${divalhr.invitations.jobs.fast-interval:PT1M}")
  public void fast() {
    run("acceptance_recovery", jobs::recoverStaleAcceptances);
    run("credential_setup", jobs::retryCredentialSetups);
    run("delivery_stale", jobs::failStaleDeliveries);
  }

  /** Every five minutes: expiry and retention. */
  @Scheduled(fixedDelayString = "${divalhr.invitations.jobs.slow-interval:PT5M}")
  public void slow() {
    run("expire", jobs::expireDue);
    run("retention", jobs::deleteRetained);
  }

  private static void run(String job, java.util.function.IntSupplier work) {
    try {
      work.getAsInt();
    } catch (RuntimeException failed) {
      LOG.atWarn()
          .addKeyValue("job", job)
          .addKeyValue("error", failed.getClass().getSimpleName())
          .log("invitation_job_failed");
    }
  }
}

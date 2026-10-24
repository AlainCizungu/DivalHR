package com.divalhr.core.integrations.messaging.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the WhatsApp jobs on a fixed delay (D111-9); disabled in integration tests. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "divalhr.messaging.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class MessagingJobScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(MessagingJobScheduler.class);

  private final MessagingJobs jobs;

  /**
   * Creates the scheduler.
   *
   * @param jobs the jobs
   */
  public MessagingJobScheduler(MessagingJobs jobs) {
    this.jobs = jobs;
  }

  /** One tick; a failure is logged by class only and never stops the schedule. */
  @Scheduled(fixedDelayString = "${divalhr.messaging.jobs.interval:PT5S}")
  void tick() {
    try {
      jobs.runOnce();
    } catch (RuntimeException failure) {
      LOG.atWarn()
          .addKeyValue("operation", "messaging.jobs")
          .addKeyValue("error", failure.getClass().getSimpleName())
          .log("messaging_jobs_failed");
    }
  }
}

package com.divalhr.core.integrations.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the webhook jobs on a fixed delay (D110-6); disabled in integration tests. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "divalhr.integrations.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class WebhookJobScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(WebhookJobScheduler.class);

  private final WebhookJobs jobs;

  /**
   * Creates the scheduler.
   *
   * @param jobs the jobs
   */
  public WebhookJobScheduler(WebhookJobs jobs) {
    this.jobs = jobs;
  }

  /** One tick; a failure is logged by class only and never stops the schedule. */
  @Scheduled(fixedDelayString = "${divalhr.integrations.jobs.interval:PT5S}")
  void tick() {
    try {
      jobs.runOnce();
    } catch (RuntimeException failure) {
      LOG.atWarn()
          .addKeyValue("operation", "webhook.jobs")
          .addKeyValue("error", failure.getClass().getSimpleName())
          .log("webhook_jobs_failed");
    }
  }
}

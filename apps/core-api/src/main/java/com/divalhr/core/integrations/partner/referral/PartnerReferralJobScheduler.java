package com.divalhr.core.integrations.partner.referral;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the referral delivery on a fixed delay (MVP-120F); disabled in integration tests. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "divalhr.integrations.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PartnerReferralJobScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(PartnerReferralJobScheduler.class);

  private final PartnerReferralDelivery delivery;

  /**
   * Creates the scheduler.
   *
   * @param delivery the delivery service
   */
  public PartnerReferralJobScheduler(PartnerReferralDelivery delivery) {
    this.delivery = delivery;
  }

  /** One tick; a failure is logged by class only and never stops the schedule. */
  @Scheduled(fixedDelayString = "${divalhr.integrations.jobs.interval:PT5S}")
  void tick() {
    try {
      delivery.runOnce();
    } catch (RuntimeException failure) {
      LOG.atWarn()
          .addKeyValue("operation", "partner-referral.delivery")
          .addKeyValue("error", failure.getClass().getSimpleName())
          .log("partner_referral_jobs_failed");
    }
  }
}

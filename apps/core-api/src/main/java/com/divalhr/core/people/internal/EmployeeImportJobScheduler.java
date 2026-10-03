package com.divalhr.core.people.internal;

import com.divalhr.core.people.application.EmployeeImportJobs;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the employee import jobs on a fixed delay (MVP-020). Disabled with {@code
 * divalhr.employee-import.jobs.enabled=false} (integration tests call the jobs directly). A failed
 * run is logged by exception type only and retried on the next run.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "divalhr.employee-import.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class EmployeeImportJobScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(EmployeeImportJobScheduler.class);

  private final EmployeeImportJobs jobs;

  /**
   * Creates the scheduler.
   *
   * @param jobs employee import jobs
   */
  public EmployeeImportJobScheduler(EmployeeImportJobs jobs) {
    this.jobs = jobs;
  }

  /** Every minute: expiry of open imports past their staging time. */
  @Scheduled(fixedDelayString = "${divalhr.employee-import.jobs.expiry-interval:PT1M}")
  public void expire() {
    run("expire", jobs::expireDue);
  }

  /** Every hour: deletion of closed imports past their retention. */
  @Scheduled(fixedDelayString = "${divalhr.employee-import.jobs.retention-interval:PT1H}")
  public void retention() {
    run("retention", jobs::deleteRetained);
  }

  private static void run(String job, IntSupplier work) {
    try {
      work.getAsInt();
    } catch (RuntimeException failed) {
      LOG.atWarn()
          .addKeyValue("job", job)
          .addKeyValue("error", failed.getClass().getSimpleName())
          .log("employee_import_job_failed");
    }
  }
}

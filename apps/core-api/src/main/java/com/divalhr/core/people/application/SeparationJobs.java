package com.divalhr.core.people.application;

import com.divalhr.core.people.internal.JdbcSeparationRepository;
import com.divalhr.core.people.internal.JdbcSeparationRepository.SeparationRecord;
import com.divalhr.core.platform.web.CorrelationId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The separation effective-date job (MVP-022): marks scheduled separations whose instant (the start
 * of the day after the last day, organization time zone) passed as EFFECTIVE, with audit and
 * outbox. Effectiveness never depends on it: the timeline was closed at commit and access denial
 * follows the revocation instant. Rows are claimed with {@code SKIP LOCKED}; logs carry counts
 * only.
 */
@Component
public class SeparationJobs {

  /** Job outcome metric (tag {@code job}). */
  public static final String METRIC = "divalhr.separation.jobs";

  private static final Logger LOG = LoggerFactory.getLogger(SeparationJobs.class);
  private static final int BATCH = 100;

  private final JdbcSeparationRepository separations;
  private final SeparationEvents events;
  private final TransactionTemplate transactions;
  private final MeterRegistry registry;

  /**
   * Creates the job.
   *
   * @param separations separation repository
   * @param events audit and outbox
   * @param transactions transaction template
   * @param registry metrics
   */
  public SeparationJobs(
      JdbcSeparationRepository separations,
      SeparationEvents events,
      TransactionTemplate transactions,
      MeterRegistry registry) {
    this.separations = separations;
    this.events = events;
    this.transactions = transactions;
    this.registry = registry;
  }

  /**
   * Marks due separations effective.
   *
   * @return separations marked
   */
  public int markEffective() {
    String correlationId = CorrelationId.resolve(null);
    Integer marked =
        transactions.execute(
            status -> {
              List<SeparationRecord> due = separations.markDueEffective(BATCH);
              Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
              for (SeparationRecord separation : due) {
                events.effective(
                    separation.tenant(),
                    separation.id(),
                    separation.employeeId(),
                    separation.employmentId(),
                    separation.version(),
                    now,
                    correlationId);
              }
              return due.size();
            });
    int count = marked == null ? 0 : marked;
    if (count > 0) {
      Counter.builder(METRIC)
          .description("Separations marked effective")
          .tag("job", "effective")
          .register(registry)
          .increment(count);
      LOG.atInfo()
          .addKeyValue("job", "effective")
          .addKeyValue("count", count)
          .log("separation_job_run");
    }
    return count;
  }
}

package com.divalhr.core.people.application;

import com.divalhr.core.people.domain.ImportRecord;
import com.divalhr.core.people.domain.ImportStatus;
import com.divalhr.core.people.internal.JdbcEmployeeImportRepository;
import com.divalhr.core.people.internal.TransactionLimits;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.platform.web.CorrelationId;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Background work of the employee import (MVP-020, E6, A20-2, A20-5), safe on several instances:
 *
 * <ul>
 *   <li>expiry: open imports past {@code expiresAt} become {@code EXPIRED} and their staged values
 *       are erased, with an audit record, claimed with {@code FOR UPDATE SKIP LOCKED} so a commit
 *       holding the row always wins;
 *   <li>retention: closed imports older than the result retention (30 days by default) are deleted
 *       with their rows. Employees, audit and outbox rows are never touched.
 * </ul>
 *
 * <p>Each batch is one transaction bounded by the cleanup statement timeout and a transaction
 * timeout; a timed-out batch is rolled back and retried on the next run. Logs carry counts only.
 */
@Component
public class EmployeeImportJobs {

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.employee-import");

  /** Batches per run, so one run is bounded too. */
  static final int MAX_BATCHES = 10;

  private final JdbcEmployeeImportRepository imports;
  private final EmployeeImportEvents events;
  private final TransactionLimits limits;
  private final EmployeeImportProperties properties;
  private final TransactionTemplate transactions;
  private final Clock clock;

  /**
   * Creates the jobs.
   *
   * @param imports import repository
   * @param events audit
   * @param limits transaction limits
   * @param properties settings
   * @param transactionManager transaction manager
   */
  @Autowired
  public EmployeeImportJobs(
      JdbcEmployeeImportRepository imports,
      EmployeeImportEvents events,
      TransactionLimits limits,
      EmployeeImportProperties properties,
      PlatformTransactionManager transactionManager) {
    this(imports, events, limits, properties, transactionManager, Clock.systemUTC());
  }

  EmployeeImportJobs(
      JdbcEmployeeImportRepository imports,
      EmployeeImportEvents events,
      TransactionLimits limits,
      EmployeeImportProperties properties,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.imports = imports;
    this.events = events;
    this.limits = limits;
    this.properties = properties;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setTimeout(
        (int) Math.max(1, properties.cleanupStatementTimeout().multipliedBy(3).toSeconds()));
    this.clock = clock;
  }

  /**
   * Expires due open imports.
   *
   * @return imports expired
   */
  public int expireDue() {
    int total = 0;
    for (int batch = 0; batch < MAX_BATCHES; batch++) {
      Integer done =
          transactions.execute(
              status -> {
                limits.apply(properties.cleanupStatementTimeout());
                Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
                String correlationId = CorrelationId.resolve(null);
                List<Map.Entry<TenantId, UUID>> due =
                    imports.claimExpired(now, properties.cleanupBatchSize());
                for (Map.Entry<TenantId, UUID> entry : due) {
                  ImportRecord record =
                      imports.find(entry.getKey(), entry.getValue()).orElseThrow();
                  imports.markClosed(entry.getKey(), entry.getValue(), ImportStatus.EXPIRED, now);
                  events.closed(
                      entry.getKey(),
                      EmployeeImportEvents.EXPIRY_ACTOR,
                      "employee-import.expire",
                      record,
                      now,
                      correlationId);
                }
                return due.size();
              });
      int count = done == null ? 0 : done;
      total += count;
      if (count < properties.cleanupBatchSize()) {
        break;
      }
    }
    if (total > 0) {
      LOG.atInfo().addKeyValue("expired", total).log("employee_import_expired");
    }
    return total;
  }

  /**
   * Deletes closed imports past the result retention.
   *
   * @return imports deleted
   */
  public int deleteRetained() {
    int total = 0;
    for (int batch = 0; batch < MAX_BATCHES; batch++) {
      Integer done =
          transactions.execute(
              status -> {
                limits.apply(properties.cleanupStatementTimeout());
                Instant before = Instant.now(clock).minus(properties.resultRetention());
                return imports.deleteClosedBefore(before, properties.cleanupBatchSize());
              });
      int count = done == null ? 0 : done;
      total += count;
      if (count < properties.cleanupBatchSize()) {
        break;
      }
    }
    if (total > 0) {
      LOG.atInfo().addKeyValue("deleted", total).log("employee_import_retention");
    }
    return total;
  }
}

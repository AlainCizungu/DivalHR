package com.divalhr.core.people.application;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds of the employee import ({@code divalhr.employee-import}; MVP-020, decisions E6, E12, E16
 * and amendments A20-2, A20-5, A20-6).
 *
 * <p>The 2 MiB body cap is a deliberately conservative transport limit sized for expected pilot
 * files (about 1,000 rows of short names and codes), not the UTF-8 worst case of 1,000 rows × 9
 * cells × 160 code points; text limits are counted separately in Unicode code points after NFC.
 *
 * @param maxBytes body cap, counted while streaming (default 2 MiB; 256 KiB to 8 MiB)
 * @param maxRows data rows per file (default 1,000; 100 to 5,000)
 * @param stagingTtl how long an open import keeps its staged values (default 2 h; 15 min to 24 h)
 * @param resultRetention how long a closed import's details are kept (default 30 days; 1 to 90
 *     days); an operational product policy, not a statutory period
 * @param maxOpenImports open imports per tenant (default 3; 1 to 10)
 * @param uploadTimeout deadline for receiving the whole body, before any transaction (default 30 s;
 *     5 to 120 s)
 * @param validationStatementTimeout per-statement limit while staging (default 5 s; 1 to 30 s)
 * @param validationTransactionTimeout staging transaction limit (default 15 s; 5 to 60 s)
 * @param commitStatementTimeout per-statement limit of the commit (default 10 s; 1 to 60 s)
 * @param commitTransactionTimeout commit transaction limit (default 30 s; 5 to 120 s)
 * @param cleanupStatementTimeout per-statement limit of the expiry and retention jobs (default 10
 *     s; 1 to 60 s)
 * @param cleanupBatchSize imports handled per job batch (default 100; 10 to 1,000)
 */
@ConfigurationProperties("divalhr.employee-import")
public record EmployeeImportProperties(
    Integer maxBytes,
    Integer maxRows,
    Duration stagingTtl,
    Duration resultRetention,
    Integer maxOpenImports,
    Duration uploadTimeout,
    Duration validationStatementTimeout,
    Duration validationTransactionTimeout,
    Duration commitStatementTimeout,
    Duration commitTransactionTimeout,
    Duration cleanupStatementTimeout,
    Integer cleanupBatchSize) {

  /** Applies defaults and enforces bounds. */
  public EmployeeImportProperties {
    maxBytes = Objects.requireNonNullElse(maxBytes, 2 * 1024 * 1024);
    maxRows = Objects.requireNonNullElse(maxRows, 1_000);
    stagingTtl = Objects.requireNonNullElse(stagingTtl, Duration.ofHours(2));
    resultRetention = Objects.requireNonNullElse(resultRetention, Duration.ofDays(30));
    maxOpenImports = Objects.requireNonNullElse(maxOpenImports, 3);
    uploadTimeout = Objects.requireNonNullElse(uploadTimeout, Duration.ofSeconds(30));
    validationStatementTimeout =
        Objects.requireNonNullElse(validationStatementTimeout, Duration.ofSeconds(5));
    validationTransactionTimeout =
        Objects.requireNonNullElse(validationTransactionTimeout, Duration.ofSeconds(15));
    commitStatementTimeout =
        Objects.requireNonNullElse(commitStatementTimeout, Duration.ofSeconds(10));
    commitTransactionTimeout =
        Objects.requireNonNullElse(commitTransactionTimeout, Duration.ofSeconds(30));
    cleanupStatementTimeout =
        Objects.requireNonNullElse(cleanupStatementTimeout, Duration.ofSeconds(10));
    cleanupBatchSize = Objects.requireNonNullElse(cleanupBatchSize, 100);
    within("max-bytes", maxBytes, 256 * 1024, 8 * 1024 * 1024);
    within("max-rows", maxRows, 100, 5_000);
    within("staging-ttl", stagingTtl, Duration.ofMinutes(15), Duration.ofHours(24));
    within("result-retention", resultRetention, Duration.ofDays(1), Duration.ofDays(90));
    within("max-open-imports", maxOpenImports, 1, 10);
    within("upload-timeout", uploadTimeout, Duration.ofSeconds(5), Duration.ofSeconds(120));
    within(
        "validation-statement-timeout",
        validationStatementTimeout,
        Duration.ofSeconds(1),
        Duration.ofSeconds(30));
    within(
        "validation-transaction-timeout",
        validationTransactionTimeout,
        Duration.ofSeconds(5),
        Duration.ofSeconds(60));
    within(
        "commit-statement-timeout",
        commitStatementTimeout,
        Duration.ofSeconds(1),
        Duration.ofSeconds(60));
    within(
        "commit-transaction-timeout",
        commitTransactionTimeout,
        Duration.ofSeconds(5),
        Duration.ofSeconds(120));
    within(
        "cleanup-statement-timeout",
        cleanupStatementTimeout,
        Duration.ofSeconds(1),
        Duration.ofSeconds(60));
    within("cleanup-batch-size", cleanupBatchSize, 10, 1_000);
  }

  private static void within(String name, int value, int min, int max) {
    if (value < min || value > max) {
      throw new IllegalStateException(
          "divalhr.employee-import." + name + " must be " + min + ".." + max);
    }
  }

  private static void within(String name, Duration value, Duration min, Duration max) {
    if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
      throw new IllegalStateException(
          "divalhr.employee-import." + name + " must be " + min + ".." + max);
    }
  }
}

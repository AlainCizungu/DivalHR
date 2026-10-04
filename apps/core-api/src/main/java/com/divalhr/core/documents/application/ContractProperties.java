package com.divalhr.core.documents.application;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds of contract writes and of the integrity job ({@code divalhr.contracts}; MVP-030).
 *
 * @param statementTimeout per-statement limit of a write (default 5 s; 1 to 30 s)
 * @param transactionTimeout write transaction limit (default 15 s; 5 to 60 s)
 * @param integrityBatchSize contracts checked per integrity batch (default 200; 1 to 1000)
 */
@ConfigurationProperties("divalhr.contracts")
public record ContractProperties(
    Duration statementTimeout, Duration transactionTimeout, Integer integrityBatchSize) {

  /** Applies defaults and enforces bounds. */
  public ContractProperties {
    statementTimeout = Objects.requireNonNullElse(statementTimeout, Duration.ofSeconds(5));
    transactionTimeout = Objects.requireNonNullElse(transactionTimeout, Duration.ofSeconds(15));
    integrityBatchSize = Objects.requireNonNullElse(integrityBatchSize, 200);
    if (statementTimeout.compareTo(Duration.ofSeconds(1)) < 0
        || statementTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
      throw new IllegalArgumentException("statement-timeout must be between 1 s and 30 s");
    }
    if (transactionTimeout.compareTo(Duration.ofSeconds(5)) < 0
        || transactionTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
      throw new IllegalArgumentException("transaction-timeout must be between 5 s and 60 s");
    }
    if (integrityBatchSize < 1 || integrityBatchSize > 1000) {
      throw new IllegalArgumentException("integrity-batch-size must be between 1 and 1000");
    }
  }
}

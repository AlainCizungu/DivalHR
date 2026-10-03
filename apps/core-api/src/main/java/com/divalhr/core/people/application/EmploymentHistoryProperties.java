package com.divalhr.core.people.application;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds of employment history changes ({@code divalhr.employment-history}; MVP-021, H8).
 *
 * <p>The retroactive window is a configurable pilot business rule, not a statutory period.
 *
 * @param retroactiveDays how many days before the business date a change or corrected row may still
 *     start (default 60; 0 to 366)
 * @param statementTimeout per-statement limit of a change or cancellation (default 5 s; 1 to 30 s)
 * @param transactionTimeout change or cancellation transaction limit (default 15 s; 5 to 60 s)
 */
@ConfigurationProperties("divalhr.employment-history")
public record EmploymentHistoryProperties(
    Integer retroactiveDays, Duration statementTimeout, Duration transactionTimeout) {

  /** Applies defaults and enforces bounds. */
  public EmploymentHistoryProperties {
    retroactiveDays = Objects.requireNonNullElse(retroactiveDays, 60);
    statementTimeout = Objects.requireNonNullElse(statementTimeout, Duration.ofSeconds(5));
    transactionTimeout = Objects.requireNonNullElse(transactionTimeout, Duration.ofSeconds(15));
    if (retroactiveDays < 0 || retroactiveDays > 366) {
      throw new IllegalArgumentException("retroactive-days must be between 0 and 366");
    }
    if (statementTimeout.compareTo(Duration.ofSeconds(1)) < 0
        || statementTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
      throw new IllegalArgumentException("statement-timeout must be between 1 s and 30 s");
    }
    if (transactionTimeout.compareTo(Duration.ofSeconds(5)) < 0
        || transactionTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
      throw new IllegalArgumentException("transaction-timeout must be between 5 s and 60 s");
    }
  }
}

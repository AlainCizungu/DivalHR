package com.divalhr.core.people.internal;

import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-transaction database limits of the employee import (MVP-020, A20-5). {@code SET LOCAL}
 * applies until the end of the current transaction only, so the pooled connection is unaffected.
 */
@Component
public class TransactionLimits {

  private final JdbcClient jdbc;

  /**
   * Creates the helper.
   *
   * @param jdbc JDBC client
   */
  public TransactionLimits(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Bounds every following statement and lock wait of the current transaction.
   *
   * @param statementTimeout per-statement limit
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void apply(Duration statementTimeout) {
    long millis = Math.max(1, statementTimeout.toMillis());
    // Values are server-owned integers; SET does not accept bind parameters.
    jdbc.sql("SET LOCAL statement_timeout = " + millis).update();
    jdbc.sql("SET LOCAL lock_timeout = " + millis).update();
  }
}

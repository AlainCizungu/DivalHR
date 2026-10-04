package com.divalhr.core.documents.internal;

import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-transaction database limits of contract writes (MVP-030; the documents module keeps its own
 * copy of the MVP-020 helper). {@code SET LOCAL} applies until the end of the current transaction
 * only, so the pooled connection is unaffected.
 */
@Component
public class ContractTransactionLimits {

  private final JdbcClient jdbc;

  /**
   * Creates the helper.
   *
   * @param jdbc JDBC client
   */
  public ContractTransactionLimits(JdbcClient jdbc) {
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

package com.divalhr.core.platform.audit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only writer of {@code platform.authorization_denial} (MVP-013, D3 and A13-6).
 *
 * <p>It owns a dedicated bulkhead: its own small connection pool and its own transaction manager,
 * used explicitly through a {@code REQUIRES_NEW} template. Neither is exposed as a bean, so the
 * application's primary {@code DataSource} and transaction manager are unchanged and no business
 * transaction can join, roll back or starve this write. Connection waits and statements are bounded
 * by {@link DenialAuditProperties}.
 *
 * <p>Ordinary business audit keeps using {@link AuditRecorder}, which still requires the caller's
 * transaction.
 */
@Component
public class DenialAuditStore implements DisposableBean {

  private final HikariDataSource pool;
  private final TransactionTemplate transactions;
  private final JdbcClient jdbc;
  private final AtomicLong attempts = new AtomicLong();

  /**
   * Creates the store and its bulkhead pool. The pool opens connections lazily, so start-up never
   * depends on it.
   *
   * @param connection the application's database connection details
   * @param properties bulkhead bounds
   */
  public DenialAuditStore(JdbcConnectionDetails connection, DenialAuditProperties properties) {
    HikariConfig config = new HikariConfig();
    config.setPoolName("divalhr-denial-audit");
    config.setJdbcUrl(connection.getJdbcUrl());
    config.setUsername(connection.getUsername());
    config.setPassword(connection.getPassword());
    config.setDriverClassName(connection.getDriverClassName());
    config.setMaximumPoolSize(properties.poolSize());
    config.setMinimumIdle(0);
    config.setConnectionTimeout(properties.connectionTimeout().toMillis());
    config.setValidationTimeout(Math.min(properties.connectionTimeout().toMillis(), 1_000));
    config.setInitializationFailTimeout(-1);
    long statementMillis = properties.statementTimeout().toMillis();
    config.setConnectionInitSql(
        "SET statement_timeout = " + statementMillis + "; SET lock_timeout = " + statementMillis);
    this.pool = new HikariDataSource(config);
    DataSourceTransactionManager manager = new DataSourceTransactionManager(pool);
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.transactions.setTimeout((int) Math.max(1, (statementMillis + 999) / 1000));
    this.jdbc = JdbcClient.create(pool);
  }

  /**
   * Commits one row in its own transaction on the bulkhead.
   *
   * @param denial the row
   * @throws RuntimeException when the row cannot commit; the caller keeps the request denied
   */
  public void insert(AuthorizationDenial denial) {
    attempts.incrementAndGet();
    transactions.executeWithoutResult(
        status ->
            jdbc.sql(
                    """
                    INSERT INTO platform.authorization_denial
                      (id, occurred_at, actor_subject, action, operation, scope, stage, tenant_id,
                       correlation_id)
                    VALUES (:id, :occurredAt, :actor, :action, :operation, :scope, :stage,
                            :tenantId, :correlationId)
                    """)
                .param("id", denial.id())
                .param("occurredAt", Timestamp.from(denial.occurredAt()))
                .param("actor", denial.actorSubject())
                .param("action", AuthorizationDenial.ACTION)
                .param("operation", denial.operation())
                .param("scope", denial.scope().value())
                .param("stage", denial.stage().value())
                .param("tenantId", denial.tenantId())
                .param("correlationId", denial.correlationId())
                .update());
  }

  /**
   * Write attempts since start-up (monitoring and tests: allowed and suppressed requests never
   * attempt a write).
   *
   * @return attempts, successful or not
   */
  public long writeAttempts() {
    return attempts.get();
  }

  @Override
  public void destroy() {
    pool.close();
  }
}

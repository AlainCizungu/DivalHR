package com.divalhr.core.people.application;

import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository;
import com.divalhr.core.platform.tenancy.TenantId;
import org.springframework.stereotype.Component;

/**
 * The tenant's manager-graph transaction lock (ADR 0008 step 1) for code outside employment history
 * (MVP-041B, D41B-5): leave decisions routed to a manager take exactly the lock that employment
 * changes, cancellations, separations and the V14/V15 manager triggers take, through the one key
 * definition in {@link JdbcEmploymentHistoryRepository#lockManagerGraph}. It is exclusive: every
 * manager-history write and every manager decision of the tenant serialize on it.
 */
@Component
public class ManagerGraphLock {

  private final JdbcEmploymentHistoryRepository history;

  /**
   * Creates the lock.
   *
   * @param history employment history storage (owner of the key)
   */
  public ManagerGraphLock(JdbcEmploymentHistoryRepository history) {
    this.history = history;
  }

  /**
   * Takes the lock until the caller's transaction ends (MANDATORY: there must be one).
   *
   * @param tenant verified tenant
   */
  public void lock(TenantId tenant) {
    history.lockManagerGraph(tenant);
  }
}

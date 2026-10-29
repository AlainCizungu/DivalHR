package com.divalhr.core.payroll.application;

import java.sql.SQLException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * Recognizes the database time limits of compensation work (MVP-093A; the payroll module keeps its
 * own copy of the helper of the other modules): a statement or lock timeout ({@code SET LOCAL
 * statement_timeout} / {@code lock_timeout}, SQLSTATE 57014 and 55P03) or an expired transaction
 * timeout. In every case the transaction has been rolled back.
 */
final class DatabaseTimeouts {

  private DatabaseTimeouts() {}

  static boolean isTimeout(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof QueryTimeoutException
          || cause instanceof TransactionTimedOutException
          || cause instanceof CannotAcquireLockException
          || cause instanceof PessimisticLockingFailureException) {
        return true;
      }
      if (cause instanceof SQLException sql
          && ("57014".equals(sql.getSQLState()) || "55P03".equals(sql.getSQLState()))) {
        return true;
      }
    }
    return false;
  }
}

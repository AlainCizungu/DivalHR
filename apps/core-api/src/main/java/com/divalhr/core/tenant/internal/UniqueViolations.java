package com.divalhr.core.tenant.internal;

import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DuplicateKeyException;

/**
 * Identifies which unique constraint a duplicate-key failure violated, so that only the named code
 * indexes map to {@code DUPLICATE_*_CODE}. Nothing from the database message (SQL, values or
 * details) is ever exposed.
 */
final class UniqueViolations {

  private UniqueViolations() {}

  /**
   * Returns the violated constraint name when the exception is a PostgreSQL unique violation.
   *
   * @param exception translated duplicate-key exception
   * @return constraint name
   */
  static Optional<String> constraint(DuplicateKeyException exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof PSQLException psql) {
        ServerErrorMessage message = psql.getServerErrorMessage();
        return message == null ? Optional.empty() : Optional.ofNullable(message.getConstraint());
      }
    }
    return Optional.empty();
  }
}

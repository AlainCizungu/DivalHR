package com.divalhr.core.operations.application;

import com.divalhr.core.operations.internal.TaskConstraintViolations;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared transaction and failure helpers of the task services (MVP-080). */
final class TaskTransitions {

  /** Explicit database timeout of every read and command transaction (D80-11). */
  static final Duration TIMEOUT = Duration.ofSeconds(15);

  private TaskTransitions() {}

  /**
   * A bounded read transaction; the result is returned only after it committed (so a disclosure is
   * audited before anything is returned).
   *
   * @param transactionManager transaction manager
   * @param work the read
   * @param <T> result type
   * @return the result
   */
  static <T> T read(PlatformTransactionManager transactionManager, Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setTimeout((int) TIMEOUT.toSeconds());
    T result = template.execute(status -> work.get());
    if (result == null) {
      throw new IllegalStateException("read transaction returned nothing");
    }
    return result;
  }

  /**
   * An integrity failure of a transition. The application checks the version and the transition
   * under the row lock, so a guard that still fires is a defect: it is never turned into a business
   * answer, and no database text is exposed.
   *
   * @param violated the failure
   * @return the exception to throw
   */
  static RuntimeException inconsistent(DataIntegrityViolationException violated) {
    if (TaskConstraintViolations.constraint(violated)
        .filter(TaskConstraintViolations.CONSISTENCY::contains)
        .isPresent()) {
      return new IllegalStateException("task transition inconsistent with its record");
    }
    return violated;
  }
}

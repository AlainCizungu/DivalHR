package com.divalhr.core.support;

import com.divalhr.core.payroll.application.PaymentProviderGateway;
import com.divalhr.core.payroll.domain.PaymentSubmissionOutcome;
import com.divalhr.core.payroll.domain.PaymentSubmissionProfile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * MVP-095B test connector (replaces the HTTPS adapter in the shared test context): returns scripted
 * results, records every call (request ID, key version, signature, a copy of the body) and never
 * opens a socket. With nothing scripted it accepts with a reference derived from the request ID.
 */
public final class FakePaymentProviderGateway implements PaymentProviderGateway {

  /**
   * One recorded call.
   *
   * @param requestId stable request ID
   * @param keyVersion key version header
   * @param signature signature header
   * @param body copy of the canonical bytes
   */
  public record Call(UUID requestId, int keyVersion, String signature, byte[] body) {
    /**
     * @return the body as text
     */
    public String text() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  private final Deque<Supplier<Result>> script = new ArrayDeque<>();
  private final List<Call> calls = new ArrayList<>();

  /**
   * Queues the next result.
   *
   * @param outcome the outcome
   * @param reference the reference, or {@code null}
   */
  public synchronized void then(PaymentSubmissionOutcome outcome, String reference) {
    script.add(() -> new Result(outcome, reference));
  }

  /**
   * Queues an arbitrary action (for example advancing a clock) whose result is returned.
   *
   * @param action the action
   */
  public synchronized void thenDo(Supplier<Result> action) {
    script.add(action);
  }

  /**
   * Queues a failure to throw.
   *
   * @param failure the failure
   */
  public synchronized void thenThrow(RuntimeException failure) {
    script.add(
        () -> {
          throw failure;
        });
  }

  /**
   * @return the recorded calls
   */
  public synchronized List<Call> calls() {
    return List.copyOf(calls);
  }

  /** Forgets scripted results and calls. */
  public synchronized void reset() {
    script.clear();
    calls.clear();
  }

  @Override
  public Result send(
      PaymentSubmissionProfile profile,
      UUID requestId,
      int keyVersion,
      String signature,
      byte[] body) {
    Supplier<Result> next;
    synchronized (this) {
      calls.add(new Call(requestId, keyVersion, signature, body.clone()));
      next = script.poll();
    }
    if (next != null) {
      return next.get();
    }
    String reference =
        "SYNTH-" + requestId.toString().replace("-", "").substring(0, 12).toUpperCase();
    return new Result(PaymentSubmissionOutcome.ACCEPTED, reference);
  }

  /** Registers the fake as the primary connector. */
  @TestConfiguration
  public static class Config {
    @Bean
    @Primary
    FakePaymentProviderGateway fakePaymentProviderGateway() {
      return new FakePaymentProviderGateway();
    }
  }
}

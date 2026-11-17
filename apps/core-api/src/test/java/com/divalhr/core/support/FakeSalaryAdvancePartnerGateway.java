package com.divalhr.core.support;

import com.divalhr.core.payroll.application.SalaryAdvancePartnerGateway;
import com.divalhr.core.payroll.application.SalaryAdvancePartnerProfile;
import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffOutcome;
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
 * MVP-120B test connector (replaces the HTTPS adapter in the shared test context): returns scripted
 * results, records every delivery and inquiry (application id, key version, timestamp, signature, a
 * copy of the body) and never opens a socket. With nothing scripted a delivery is acknowledged with
 * a reference derived from the application id and an inquiry is inconclusive.
 */
public final class FakeSalaryAdvancePartnerGateway implements SalaryAdvancePartnerGateway {

  /**
   * One recorded delivery or inquiry.
   *
   * @param applicationId the opaque application id
   * @param keyVersion key version header
   * @param timestamp signed timestamp (epoch seconds)
   * @param signature signature header
   * @param body copy of the canonical bytes
   */
  public record Call(
      UUID applicationId, String keyVersion, long timestamp, String signature, byte[] body) {
    /**
     * @return the body as text
     */
    public String text() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  private final Deque<Supplier<Result>> script = new ArrayDeque<>();
  private final List<Call> calls = new ArrayList<>();
  private final Deque<Supplier<InquiryResult>> inquiryScript = new ArrayDeque<>();
  private final List<Call> inquiryCalls = new ArrayList<>();

  /**
   * Queues the next delivery result.
   *
   * @param outcome the outcome
   * @param reference the reference, or {@code null}
   */
  public synchronized void then(SalaryAdvanceHandoffOutcome outcome, String reference) {
    script.add(() -> new Result(outcome, reference));
  }

  /**
   * Queues an arbitrary action whose result is returned by the next delivery.
   *
   * @param action the action
   */
  public synchronized void thenDo(Supplier<Result> action) {
    script.add(action);
  }

  /**
   * Queues a failure to throw from the next delivery.
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
   * Queues the next inquiry result.
   *
   * @param outcome the outcome
   * @param reference the reference, or {@code null}
   */
  public synchronized void thenInquiry(InquiryOutcome outcome, String reference) {
    inquiryScript.add(() -> new InquiryResult(outcome, reference));
  }

  /**
   * @return the recorded deliveries
   */
  public synchronized List<Call> calls() {
    return List.copyOf(calls);
  }

  /**
   * @return the recorded inquiries
   */
  public synchronized List<Call> inquiryCalls() {
    return List.copyOf(inquiryCalls);
  }

  /** Forgets scripted results and calls. */
  public synchronized void reset() {
    script.clear();
    calls.clear();
    inquiryScript.clear();
    inquiryCalls.clear();
  }

  @Override
  public Result send(
      SalaryAdvancePartnerProfile profile,
      UUID applicationId,
      String keyVersion,
      long timestamp,
      String signature,
      byte[] body) {
    Supplier<Result> next;
    synchronized (this) {
      calls.add(new Call(applicationId, keyVersion, timestamp, signature, body.clone()));
      next = script.poll();
    }
    if (next != null) {
      return next.get();
    }
    return new Result(
        SalaryAdvanceHandoffOutcome.RECEIVED_ACKNOWLEDGED,
        "SYNTH-" + applicationId.toString().replace("-", "").substring(0, 12).toUpperCase());
  }

  @Override
  public InquiryResult inquire(
      SalaryAdvancePartnerProfile profile,
      UUID applicationId,
      String keyVersion,
      long timestamp,
      String signature,
      byte[] body) {
    Supplier<InquiryResult> next;
    synchronized (this) {
      inquiryCalls.add(new Call(applicationId, keyVersion, timestamp, signature, body.clone()));
      next = inquiryScript.poll();
    }
    return next != null ? next.get() : new InquiryResult(InquiryOutcome.INCONCLUSIVE, null);
  }

  /** Registers the fake as the primary connector. */
  @TestConfiguration
  public static class Config {
    @Bean
    @Primary
    FakeSalaryAdvancePartnerGateway fakeSalaryAdvancePartnerGateway() {
      return new FakeSalaryAdvancePartnerGateway();
    }
  }
}

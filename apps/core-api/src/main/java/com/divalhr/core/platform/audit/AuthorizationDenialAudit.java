package com.divalhr.core.platform.audit;

import com.divalhr.core.platform.audit.AuthorizationDenial.Scope;
import com.divalhr.core.platform.audit.AuthorizationDenial.Stage;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.platform.web.CorrelationId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Records privileged authorization denials (MVP-013, Issue #43). Called on the deny path only,
 * after the decision and before the denial is thrown; whatever happens here, the request stays
 * denied with its normal response (D4).
 *
 * <p>Per request, at most one call does anything (one write attempt at most): later calls for the
 * same request return {@link Outcome#ALREADY_RECORDED}. An eligible denial needs a verified,
 * non-blank JWT subject (A13-3) and a named operation; the budget is checked before the bulkhead is
 * touched (A13-2); the row is written in its own transaction ({@link DenialAuditStore}).
 *
 * <p>Telemetry carries bounded labels only ({@code outcome}, {@code stage}, {@code scope}). The
 * subject never appears in logs or metrics, and a storage failure is logged by exception type only,
 * never by message.
 */
@Component
public class AuthorizationDenialAudit {

  /** Counter of denial-audit outcomes. */
  public static final String METRIC = "divalhr.authorization.denial_audit";

  /** Stage label of the never-durable subject check (stage 1). */
  public static final String SUBJECT_MISSING = "subject_missing";

  /** Request attribute set by the first call for a request. */
  static final String RECORDED = AuthorizationDenialAudit.class.getName() + ".RECORDED";

  private static final Logger SECURITY_LOG = LoggerFactory.getLogger("divalhr.security");

  /** What happened to the denial evidence; also the {@code audit} field of the security log. */
  public enum Outcome {
    /** One row committed. */
    WRITTEN,
    /** Over the per-actor or per-instance budget: telemetry only, no write attempted. */
    SUPPRESSED,
    /** The write failed; the request is still denied (evidence gap, alerted). */
    FAILED,
    /** No verified actor or no named operation: telemetry only, never durable. */
    INELIGIBLE,
    /** An earlier call already handled this request. */
    ALREADY_RECORDED;

    /**
     * Log and metric value.
     *
     * @return lower-case name
     */
    public String value() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private final DenialAuditBudget budget;
  private final DenialAuditStore store;
  private final MeterRegistry meters;
  private final Clock clock;

  /**
   * Creates the recorder.
   *
   * @param budget flood bound
   * @param store bulkhead writer
   * @param meters meter registry
   */
  @Autowired
  public AuthorizationDenialAudit(
      DenialAuditBudget budget, DenialAuditStore store, MeterRegistry meters) {
    this(budget, store, meters, Clock.systemUTC());
  }

  AuthorizationDenialAudit(
      DenialAuditBudget budget, DenialAuditStore store, MeterRegistry meters, Clock clock) {
    this.budget = budget;
    this.store = store;
    this.meters = meters;
    this.clock = clock;
  }

  /**
   * Records a denial at a durable stage.
   *
   * @param request current request (attributes and correlation ID only; never parameters or body)
   * @param authentication current authentication
   * @param scope privileged scope
   * @param stage denying stage
   * @param operation the handler's operation name; empty means not eligible
   * @param effectiveTenant effective tenant for tenant stages after the membership gate, else null
   * @return what happened
   */
  public Outcome record(
      HttpServletRequest request,
      Authentication authentication,
      Scope scope,
      Stage stage,
      String operation,
      TenantId effectiveTenant) {
    if (!firstFor(request)) {
      return Outcome.ALREADY_RECORDED;
    }
    String subject = verifiedSubject(authentication);
    if (subject == null || operation == null || operation.isEmpty()) {
      return count(Outcome.INELIGIBLE, stage.value(), scope);
    }
    if (!budget.tryAcquire(subject)) {
      return count(Outcome.SUPPRESSED, stage.value(), scope);
    }
    try {
      store.insert(
          new AuthorizationDenial(
              UUID.randomUUID(),
              Instant.now(clock),
              subject,
              operation,
              scope,
              stage,
              effectiveTenant == null ? null : effectiveTenant.value(),
              correlationId(request)));
      return count(Outcome.WRITTEN, stage.value(), scope);
    } catch (RuntimeException failure) {
      SECURITY_LOG
          .atError()
          .addKeyValue("event", "denial_audit_failed")
          .addKeyValue("stage", stage.value())
          .addKeyValue("scope", scope.value())
          .addKeyValue("operation", operation)
          .addKeyValue("errorType", failure.getClass().getName())
          .log("denial_audit_failed");
      return count(Outcome.FAILED, stage.value(), scope);
    }
  }

  /**
   * Counts a denial without a verified subject (stage 1). Never durable and never attributed.
   *
   * @param request current request
   * @param scope privileged scope
   * @return {@link Outcome#INELIGIBLE}, or {@link Outcome#ALREADY_RECORDED}
   */
  public Outcome subjectMissing(HttpServletRequest request, Scope scope) {
    if (!firstFor(request)) {
      return Outcome.ALREADY_RECORDED;
    }
    return count(Outcome.INELIGIBLE, SUBJECT_MISSING, scope);
  }

  /** A request is handled by one thread at a time, so a plain attribute check suffices. */
  private static boolean firstFor(HttpServletRequest request) {
    if (request.getAttribute(RECORDED) != null) {
      return false;
    }
    request.setAttribute(RECORDED, Boolean.TRUE);
    return true;
  }

  private static String verifiedSubject(Authentication authentication) {
    if (authentication instanceof JwtAuthenticationToken token) {
      String subject = token.getToken().getSubject();
      return AuthorizationDenial.eligibleSubject(subject) ? subject : null;
    }
    return null;
  }

  private static String correlationId(HttpServletRequest request) {
    Object resolved = request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE);
    return CorrelationId.resolve(resolved instanceof String value ? value : null);
  }

  private Outcome count(Outcome outcome, String stage, Scope scope) {
    Counter.builder(METRIC)
        .description("Outcomes of privileged authorization-denial audit evidence")
        .tag("outcome", outcome.value())
        .tag("stage", stage)
        .tag("scope", scope.value())
        .register(meters)
        .increment();
    return outcome;
  }
}

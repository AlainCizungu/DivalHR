package com.divalhr.core.platform.audit;

import com.divalhr.core.platform.operation.OperationName;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One row of {@code platform.authorization_denial} (MVP-013, Issue #43, D1 and A13-5): a verified
 * identity attempted a privileged operation and was denied. Every field is a server-owned value;
 * there is no free-form metadata. The validation here mirrors the V12 checks exactly.
 *
 * @param id row ID
 * @param occurredAt UTC time of the decision
 * @param actorSubject the verified JWT {@code sub}, unchanged (never blank)
 * @param operation the handler's stable operation name
 * @param scope platform or tenant
 * @param stage the gate that denied the request
 * @param tenantId the effective tenant, only for tenant-scoped stages after the membership gate
 * @param correlationId the request's validated or generated correlation ID (a join key only)
 */
public record AuthorizationDenial(
    UUID id,
    Instant occurredAt,
    String actorSubject,
    String operation,
    Scope scope,
    Stage stage,
    UUID tenantId,
    String correlationId) {

  /** The single audit action. */
  public static final String ACTION = "authorization.denied";

  /** Same expression as {@code authorization_denial_correlation_format}. */
  private static final java.util.regex.Pattern CORRELATION =
      java.util.regex.Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

  /** Privileged scope of the denied operation. */
  public enum Scope {
    /** {@code @PlatformScoped}: never has a tenant. */
    PLATFORM,
    /** {@code @TenantScoped} (including {@code @TenantAdminOperation}). */
    TENANT;

    /**
     * Database and telemetry value.
     *
     * @return lower-case name
     */
    public String value() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** Durable denial stages, in gate order. Stage 1 (no verified subject) is never durable. */
  public enum Stage {
    /** Required role missing from the token. */
    ROLE,
    /** Tenant-scoped call without a valid verified tenant. */
    TENANT_CONTEXT,
    /** Privileged session without exact multifactor assurance. */
    MFA,
    /** No active membership with exactly the required role in the token's tenant. */
    MEMBERSHIP,
    /** First refusal of a per-subject limit in its window. */
    RATE_LIMIT,
    /** Spring method security denied a privileged handler that the interceptor had allowed. */
    METHOD_SECURITY;

    /**
     * Database and telemetry value.
     *
     * @return lower-case name
     */
    public String value() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** Stages a platform-scoped row may carry ({@code authorization_denial_platform_stages}). */
  public static final Set<Stage> PLATFORM_STAGES =
      Set.of(Stage.ROLE, Stage.MFA, Stage.METHOD_SECURITY);

  /** Stages after the membership gate: exactly those that carry the effective tenant. */
  public static final Set<Stage> EFFECTIVE_TENANT_STAGES =
      Set.of(Stage.RATE_LIMIT, Stage.METHOD_SECURITY);

  /** Validates the invariants the database also enforces. */
  public AuthorizationDenial {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(actorSubject, "actorSubject");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(correlationId, "correlationId");
    if (!eligibleSubject(actorSubject)) {
      throw new IllegalArgumentException("actor subject must be a verified non-blank subject");
    }
    OperationName.require(operation, "denied operation");
    if (scope == Scope.PLATFORM && !PLATFORM_STAGES.contains(stage)) {
      throw new IllegalArgumentException("stage not applicable to platform scope");
    }
    boolean effective = scope == Scope.TENANT && EFFECTIVE_TENANT_STAGES.contains(stage);
    if (effective != (tenantId != null)) {
      throw new IllegalArgumentException("tenant only for tenant stages after the membership gate");
    }
    if (!CORRELATION.matcher(correlationId).matches()) {
      throw new IllegalArgumentException("correlation ID format");
    }
  }

  /**
   * Whether a subject can be stored unchanged as actor evidence: non-blank (as the authorization
   * gate requires) and free of U+0000, which PostgreSQL text cannot hold. Nothing is ever trimmed
   * or transformed.
   *
   * @param subject verified token subject, possibly {@code null}
   * @return true when the subject is eligible
   */
  public static boolean eligibleSubject(String subject) {
    return subject != null && !subject.isBlank() && subject.indexOf('\u0000') < 0;
  }
}

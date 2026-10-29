package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.CompensationDates.Span;
import com.divalhr.core.payroll.domain.CompensationTimeline.Fact;
import com.divalhr.core.payroll.domain.CompensationTimeline.Interval;
import com.divalhr.core.payroll.domain.CompensationTimeline.Plan;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The stateless digests of compensation previews (MVP-093A, D93-8; ADR 0007): a SHA-256 over a
 * versioned canonical text of everything the preview showed and the commit will do. A commit
 * recomputes the digest from the current state; any difference is a stale preview. The change that
 * would be recorded has a fresh identifier on every evaluation, so its origin is written as {@code
 * self}. The digests are only compared and hashed, never logged, audited with their input or stored
 * beside an amount.
 */
final class CompensationDigests {

  private static final String SELF = "self";

  private CompensationDigests() {}

  /**
   * The digest of all the employee's employments (id, version, start and end).
   *
   * @param employments the employments
   * @return the digest
   */
  static String employments(List<Span> employments) {
    List<Span> ordered = new ArrayList<>(employments);
    ordered.sort(Comparator.comparing(Span::start).thenComparing(Span::id));
    StringBuilder text = new StringBuilder("DIVALHR-COMPENSATION-EMPLOYMENTS\nversion=1\n");
    for (Span span : ordered) {
      text.append(span.id())
          .append(';')
          .append(span.version())
          .append(';')
          .append(span.start())
          .append(';')
          .append(span.end() == null ? "-" : span.end().toString())
          .append('\n');
    }
    return Fingerprints.sha256(text.toString());
  }

  /**
   * The digest of a previewed command.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param draft the command
   * @param version compensation version
   * @param employmentDigest employment digest
   * @param changeId the change that would be recorded
   * @param plan what the command would do
   * @param expiresAt expiry
   * @return the digest
   */
  static String preview(
      TenantId tenant,
      UUID employeeId,
      CompensationCommands.Draft draft,
      int version,
      String employmentDigest,
      UUID changeId,
      Plan plan,
      Instant expiresAt) {
    StringBuilder text = new StringBuilder("DIVALHR-COMPENSATION-PREVIEW\nversion=1\n");
    text.append("tenant=").append(tenant).append('\n');
    text.append("employee=").append(employeeId).append('\n');
    text.append("action=").append(draft.action()).append('\n');
    text.append("effectiveFrom=").append(draft.effectiveFrom()).append('\n');
    text.append("currency=").append(draft.currency()).append('\n');
    text.append("minor=").append(draft.amount().money().minor()).append('\n');
    text.append("target=").append(draft.target() == null ? "-" : draft.target()).append('\n');
    text.append("reason=").append(draft.reason() == null ? "-" : draft.reason()).append('\n');
    text.append("explanation=")
        .append(draft.explanation() == null ? "-" : Fingerprints.sha256(draft.explanation()))
        .append('\n');
    return finish(text, version, employmentDigest, changeId, plan, expiresAt);
  }

  /**
   * The digest of a previewed cancellation.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param target the scheduled change to cancel
   * @param version compensation version
   * @param employmentDigest employment digest
   * @param plan what the cancellation would do
   * @param expiresAt expiry
   * @return the digest
   */
  static String cancellation(
      TenantId tenant,
      UUID employeeId,
      UUID target,
      int version,
      String employmentDigest,
      Plan plan,
      Instant expiresAt) {
    StringBuilder text = new StringBuilder("DIVALHR-COMPENSATION-CANCELLATION\nversion=1\n");
    text.append("tenant=").append(tenant).append('\n');
    text.append("employee=").append(employeeId).append('\n');
    text.append("target=").append(target).append('\n');
    return finish(text, version, employmentDigest, null, plan, expiresAt);
  }

  private static String finish(
      StringBuilder text,
      int version,
      String employmentDigest,
      UUID changeId,
      Plan plan,
      Instant expiresAt) {
    text.append("compensationVersion=").append(version).append('\n');
    text.append("employments=").append(employmentDigest).append('\n');
    for (Fact fact : plan.superseded()) {
      text.append("supersedes=").append(fact.id()).append(';');
      interval(text, fact.interval(), changeId);
    }
    for (Interval created : plan.created()) {
      text.append("creates=");
      interval(text, created, changeId);
    }
    for (Interval after : plan.after()) {
      text.append("after=");
      interval(text, after, changeId);
    }
    text.append("expiresAt=").append(expiresAt).append('\n');
    return Fingerprints.sha256(text.toString());
  }

  private static void interval(StringBuilder text, Interval interval, UUID changeId) {
    text.append(interval.origin().equals(changeId) ? SELF : interval.origin().toString())
        .append(';')
        .append(interval.money().currency())
        .append(';')
        .append(interval.money().minor())
        .append(';')
        .append(interval.from())
        .append(';')
        .append(interval.to() == null ? "-" : interval.to().toString())
        .append('\n');
  }
}

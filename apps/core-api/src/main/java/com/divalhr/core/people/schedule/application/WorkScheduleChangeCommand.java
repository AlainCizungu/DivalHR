package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.schedule.api.CancelWorkScheduleRequest;
import com.divalhr.core.people.schedule.api.ReplaceWorkScheduleRequest;
import com.divalhr.core.people.schedule.api.WorkScheduleReplacementRequest;
import com.divalhr.core.people.schedule.domain.SlotDraft;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validated cancellation and replacement requests (MVP-050B, D50B-5 and D50B-6). Only the shape is
 * checked here: the schedule ID of the path (anything malformed is the same {@code 404
 * WORK_SCHEDULE_NOT_FOUND} as an unknown schedule), the reason ({@link WorkScheduleReasonGrammar},
 * the decision-reason grammar version 1 of the leave decisions and {@code
 * people.leave_reason_valid}), the slots, the digest and the confirmation. The reason never reaches
 * a log line through {@code toString}.
 */
public final class WorkScheduleChangeCommand {

  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

  private WorkScheduleChangeCommand() {}

  /**
   * A cancellation.
   *
   * @param scheduleId the schedule
   * @param reason the reason in its stored form
   */
  public record Cancellation(UUID scheduleId, String reason) {

    @Override
    public String toString() {
      return "Cancellation[scheduleId=" + scheduleId + "]";
    }

    /**
     * The normalized cancellation and its scope, fingerprinted for idempotency.
     *
     * @param tenant verified tenant
     * @return key-sorted canonical form
     */
    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("scheduleId", scheduleId.toString());
      canonical.put("reason", reason);
      canonical.put("confirm", true);
      return canonical;
    }
  }

  /**
   * A replacement proposal: every slot of the new schedule and the reason.
   *
   * @param scheduleId the schedule being replaced
   * @param slots normalized slots, positions 1 to n
   * @param reason the reason in its stored form
   */
  public record Proposal(UUID scheduleId, List<SlotDraft> slots, String reason) {

    /** Defensively copies the slots. */
    public Proposal {
      slots = List.copyOf(slots);
    }

    @Override
    public String toString() {
      return "Proposal[scheduleId=" + scheduleId + ", slots=" + slots.size() + "]";
    }

    /**
     * The proposal as a schedule command of the original's site and period.
     *
     * @param siteId the original's site
     * @param first the original's first date
     * @param last the original's last date
     * @return the command the planner evaluates
     */
    WorkScheduleCommand command(UUID siteId, java.time.LocalDate first, java.time.LocalDate last) {
      return new WorkScheduleCommand(siteId, first, last, slots);
    }

    /**
     * The normalized proposal and its scope, fingerprinted for idempotency.
     *
     * @param tenant verified tenant
     * @return key-sorted canonical form
     */
    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("scheduleId", scheduleId.toString());
      canonical.put("reason", reason);
      List<Map<String, Object>> items = new ArrayList<>(slots.size());
      for (SlotDraft slot : slots) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("clientRef", slot.clientRef());
        item.put("start", WorkScheduleCommand.wire(slot.start()));
        item.put("end", WorkScheduleCommand.wire(slot.end()));
        item.put("labelEn", slot.labelEn());
        item.put("labelFr", slot.labelFr());
        item.put("employeeId", slot.employeeId() == null ? "" : slot.employeeId().toString());
        items.add(item);
      }
      canonical.put("slots", items);
      return canonical;
    }
  }

  /**
   * A replacement: the proposal, the reviewed preview's digest and the confirmation.
   *
   * @param proposal the proposal
   * @param previewDigest the replacement preview's digest
   */
  public record Replacement(Proposal proposal, String previewDigest) {

    @Override
    public String toString() {
      return "Replacement[" + proposal + "]";
    }

    /**
     * The normalized replacement and its scope, fingerprinted for idempotency.
     *
     * @param tenant verified tenant
     * @return key-sorted canonical form
     */
    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = proposal.canonical(tenant);
      canonical.put("previewDigest", previewDigest);
      canonical.put("confirm", true);
      return canonical;
    }
  }

  /**
   * The schedule ID of a path, or {@code 404 WORK_SCHEDULE_NOT_FOUND} for anything else.
   *
   * @param raw path value
   * @return the ID
   */
  static UUID scheduleId(String raw) {
    if (raw == null || !UUID_SHAPE.matcher(raw).matches()) {
      throw new ApiException(ErrorCode.WORK_SCHEDULE_NOT_FOUND, Map.of());
    }
    return UUID.fromString(raw.toLowerCase(Locale.ROOT));
  }

  /**
   * Validates a cancellation request.
   *
   * @param scheduleId path value (already a well-formed ID)
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the cancellation
   */
  static Cancellation cancellation(
      UUID scheduleId, String idempotencyKey, CancelWorkScheduleRequest request) {
    FieldErrors errors = new FieldErrors();
    key(errors, idempotencyKey);
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    String reason = reason(errors, request.getReason());
    if (!Boolean.TRUE.equals(request.getConfirm())) {
      errors.add("confirm", Constraint.REQUIRED);
    }
    errors.throwIfAny();
    return new Cancellation(scheduleId, reason);
  }

  /**
   * Validates a replacement preview request.
   *
   * @param scheduleId path value (already a well-formed ID)
   * @param request body
   * @return the proposal
   */
  static Proposal proposal(UUID scheduleId, WorkScheduleReplacementRequest request) {
    FieldErrors errors = new FieldErrors();
    Proposal proposal = parse(scheduleId, request, errors);
    errors.throwIfAny();
    return proposal;
  }

  /**
   * Validates a replacement request.
   *
   * @param scheduleId path value (already a well-formed ID)
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the replacement
   */
  static Replacement replacement(
      UUID scheduleId, String idempotencyKey, ReplaceWorkScheduleRequest request) {
    FieldErrors errors = new FieldErrors();
    key(errors, idempotencyKey);
    Proposal proposal = parse(scheduleId, request, errors);
    String digest = null;
    Object rawDigest = request.getPreviewDigest();
    if (rawDigest == null) {
      errors.add("previewDigest", Constraint.REQUIRED);
    } else if (!(rawDigest instanceof String text) || !DIGEST.matcher(text).matches()) {
      errors.add("previewDigest", Constraint.FORMAT);
    } else {
      digest = text;
    }
    if (!Boolean.TRUE.equals(request.getConfirm())) {
      errors.add("confirm", Constraint.REQUIRED);
    }
    errors.throwIfAny();
    return new Replacement(proposal, digest);
  }

  private static Proposal parse(
      UUID scheduleId, WorkScheduleReplacementRequest request, FieldErrors errors) {
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    List<SlotDraft> slots = WorkScheduleCommand.slots(errors, request.getSlots());
    String reason = reason(errors, request.getReason());
    return new Proposal(scheduleId, slots, reason);
  }

  private static void key(FieldErrors errors, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
  }

  /**
   * The reason in its stored form (decision-reason grammar version 1): {@code REQUIRED} when absent
   * or blank, {@code FORMAT} with a forbidden code point, {@code LENGTH} outside 2 to 500 code
   * points (the leave decisions' order).
   */
  static String reason(FieldErrors errors, Object raw) {
    if (raw == null) {
      errors.add("reason", Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text)) {
      errors.add("reason", Constraint.FORMAT);
      return null;
    }
    String stored = WorkScheduleReasonGrammar.normalize(text);
    if (stored.isEmpty()) {
      errors.add("reason", Constraint.REQUIRED);
      return null;
    }
    if (!WorkScheduleReasonGrammar.allowedCodePoints(stored)) {
      errors.add("reason", Constraint.FORMAT);
      return null;
    }
    if (!WorkScheduleReasonGrammar.isValid(stored)) {
      errors.add("reason", Constraint.LENGTH);
      return null;
    }
    return stored;
  }
}

package com.divalhr.core.people.application;

import com.divalhr.core.people.api.CancelEmploymentChangeRequest;
import com.divalhr.core.people.api.CreateEmploymentChangeRequest;
import com.divalhr.core.people.api.EmploymentChangeFields;
import com.divalhr.core.people.domain.history.AssignmentKind;
import com.divalhr.core.people.domain.history.AssignmentValue;
import com.divalhr.core.people.domain.history.ChangeReason;
import com.divalhr.core.people.domain.history.ChangeType;
import com.divalhr.core.people.domain.history.CompensationBasis;
import com.divalhr.core.people.domain.history.ContractClassification;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A validated employment change command (MVP-021). Built from the raw request values so that every
 * type, format and unknown property gives a stable field error; never logged (toString shows no
 * value).
 *
 * @param type CHANGE or CORRECTION
 * @param effectiveFrom effective date
 * @param changes per kind, the new value, or empty to end the kind (MANAGER only)
 * @param reason reason, or {@code null}
 * @param correctsAssignmentId corrected row (corrections), else {@code null}
 */
public record EmploymentChangeCommand(
    ChangeType type,
    LocalDate effectiveFrom,
    Map<AssignmentKind, Optional<AssignmentValue>> changes,
    ChangeReason reason,
    UUID correctsAssignmentId) {

  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
  private static final LocalDate MIN = LocalDate.of(1900, 1, 1);
  private static final LocalDate MAX = LocalDate.of(2999, 12, 31);
  private static final Set<String> PLACEMENT_KEYS =
      Set.of("legalEntityId", "siteId", "departmentId", "costCenterId", "teamId");

  /** Copies the changes. */
  public EmploymentChangeCommand {
    changes = Collections.unmodifiableMap(new EnumMap<>(changes));
  }

  /**
   * What the commit adds to the command.
   *
   * @param expectedVersion employment version from the preview
   * @param previewDigest digest from the preview
   * @param acknowledgeRetroactive whether a retroactive change is acknowledged
   */
  public record Confirmation(
      long expectedVersion, String previewDigest, boolean acknowledgeRetroactive) {}

  /**
   * What a cancellation repeats from its preview.
   *
   * @param expectedVersion employment version
   * @param cancellationDigest digest
   */
  public record CancellationConfirmation(long expectedVersion, String cancellationDigest) {}

  /**
   * Stable text of the command, for digests and idempotency fingerprints only.
   *
   * @return canonical text
   */
  public String canonical() {
    StringJoiner text = new StringJoiner(";");
    text.add("type=" + type.name());
    text.add("effectiveFrom=" + effectiveFrom);
    text.add("reason=" + (reason == null ? "-" : reason.name()));
    text.add("corrects=" + (correctsAssignmentId == null ? "-" : correctsAssignmentId));
    changes.forEach(
        (kind, value) ->
            text.add(kind.name() + "=" + value.map(AssignmentValue::canonical).orElse("clear")));
    return text.toString();
  }

  @Override
  public String toString() {
    return "EmploymentChangeCommand[" + type + ", " + changes.keySet() + "]";
  }

  /**
   * Validates a preview or commit body.
   *
   * @param request raw body
   * @param errors collected field errors
   * @return the command, or {@code null} when errors were recorded
   */
  static EmploymentChangeCommand parse(EmploymentChangeFields request, FieldErrors errors) {
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      return null;
    }
    for (String unknown : request.unknownProperties()) {
      errors.add(unknown, Constraint.UNKNOWN_PROPERTY);
    }
    ChangeType type = null;
    if (request.getType() == null) {
      errors.add("type", Constraint.REQUIRED);
    } else if ("CHANGE".equals(request.getType())) {
      type = ChangeType.CHANGE;
    } else if ("CORRECTION".equals(request.getType())) {
      type = ChangeType.CORRECTION;
    } else {
      errors.add("type", Constraint.FORMAT);
    }
    LocalDate effective = date(request.getEffectiveFrom(), "effectiveFrom", errors);
    Map<AssignmentKind, Optional<AssignmentValue>> changes = new EnumMap<>(AssignmentKind.class);
    if (request.getPlacement() != null) {
      placement(request.getPlacement(), errors)
          .ifPresent(p -> changes.put(AssignmentKind.PLACEMENT, Optional.of(p)));
    }
    if (request.getManager() != null) {
      manager(request.getManager(), errors).ifPresent(m -> changes.put(AssignmentKind.MANAGER, m));
    }
    if (request.getContractClassification() != null) {
      ContractClassification code =
          enumValue(
              ContractClassification.class,
              request.getContractClassification(),
              "contractClassification",
              errors);
      if (code != null) {
        changes.put(AssignmentKind.CONTRACT, Optional.of(new AssignmentValue.Contract(code)));
      }
    }
    if (request.getCompensationBasis() != null) {
      CompensationBasis code =
          enumValue(
              CompensationBasis.class, request.getCompensationBasis(), "compensationBasis", errors);
      if (code != null) {
        changes.put(
            AssignmentKind.COMPENSATION, Optional.of(new AssignmentValue.Compensation(code)));
      }
    }
    ChangeReason reason = null;
    if (request.getReasonCode() != null) {
      reason = enumValue(ChangeReason.class, request.getReasonCode(), "reasonCode", errors);
      if (reason != null
          && type != null
          && reason.correction() != (type == ChangeType.CORRECTION)) {
        errors.add("reasonCode", Constraint.FORMAT);
      }
    }
    UUID corrects = null;
    if (type == ChangeType.CORRECTION) {
      if (request.getCorrectsAssignmentId() == null) {
        errors.add("correctsAssignmentId", Constraint.REQUIRED);
      } else {
        corrects = uuid(request.getCorrectsAssignmentId(), "correctsAssignmentId", errors);
      }
      if (request.getReasonCode() == null) {
        errors.add("reasonCode", Constraint.REQUIRED);
      }
      if (changes.size() > 1) {
        errors.add("changes", Constraint.FORMAT);
      }
      changes.forEach(
          (kind, value) -> {
            if (value.isEmpty()) {
              errors.add("manager.employeeId", Constraint.REQUIRED);
            }
          });
    } else if (request.getCorrectsAssignmentId() != null) {
      errors.add("correctsAssignmentId", Constraint.FORMAT);
    }
    if (changes.isEmpty() && errors.isEmpty()) {
      errors.add("changes", Constraint.REQUIRED);
    }
    if (!errors.isEmpty() || type == null || effective == null) {
      return null;
    }
    return new EmploymentChangeCommand(type, effective, changes, reason, corrects);
  }

  /**
   * Validates the confirmation of a commit body.
   *
   * @param request raw body
   * @param errors collected field errors
   * @return the confirmation, or {@code null} when errors were recorded
   */
  static Confirmation confirmation(CreateEmploymentChangeRequest request, FieldErrors errors) {
    if (request == null) {
      return null;
    }
    Long version = version(request.getExpectedVersion(), "expectedVersion", errors);
    String digest = digest(request.getPreviewDigest(), "previewDigest", errors);
    Boolean acknowledge = null;
    if (request.getAcknowledgeRetroactive() == null) {
      errors.add("acknowledgeRetroactive", Constraint.REQUIRED);
    } else if (request.getAcknowledgeRetroactive() instanceof Boolean flag) {
      acknowledge = flag;
    } else {
      errors.add("acknowledgeRetroactive", Constraint.FORMAT);
    }
    if (version == null || digest == null || acknowledge == null) {
      return null;
    }
    return new Confirmation(version, digest, acknowledge);
  }

  /**
   * Validates a cancellation body.
   *
   * @param request raw body
   * @param errors collected field errors
   * @return the confirmation, or {@code null} when errors were recorded
   */
  static CancellationConfirmation cancellation(
      CancelEmploymentChangeRequest request, FieldErrors errors) {
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      return null;
    }
    for (String unknown : request.unknownProperties()) {
      errors.add(unknown, Constraint.UNKNOWN_PROPERTY);
    }
    Long version = version(request.getExpectedVersion(), "expectedVersion", errors);
    String digest = digest(request.getCancellationDigest(), "cancellationDigest", errors);
    if (!errors.isEmpty() || version == null || digest == null) {
      return null;
    }
    return new CancellationConfirmation(version, digest);
  }

  private static Long version(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof Integer || raw instanceof Long)) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    long value = ((Number) raw).longValue();
    if (value < 0) {
      errors.add(field, Constraint.RANGE);
      return null;
    }
    return value;
  }

  private static String digest(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !DIGEST.matcher(text).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return text;
  }

  static LocalDate date(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text)) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    LocalDate date;
    try {
      date = LocalDate.parse(text, ISO);
    } catch (DateTimeParseException malformed) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    if (date.isBefore(MIN) || date.isAfter(MAX)) {
      errors.add(field, Constraint.RANGE);
      return null;
    }
    return date;
  }

  private static UUID uuid(Object raw, String field, FieldErrors errors) {
    if (!(raw instanceof String text) || text.length() != 36) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    try {
      UUID id = UUID.fromString(text);
      if (!id.toString().equals(text.toLowerCase(java.util.Locale.ROOT))) {
        errors.add(field, Constraint.FORMAT);
        return null;
      }
      return id;
    } catch (IllegalArgumentException malformed) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
  }

  private static <E extends Enum<E>> E enumValue(
      Class<E> type, Object raw, String field, FieldErrors errors) {
    if (raw instanceof String text) {
      for (E value : type.getEnumConstants()) {
        if (value.name().equals(text)) {
          return value;
        }
      }
    }
    errors.add(field, Constraint.FORMAT);
    return null;
  }

  private static Optional<AssignmentValue.Placement> placement(Object raw, FieldErrors errors) {
    if (!(raw instanceof Map<?, ?> map)) {
      errors.add("placement", Constraint.FORMAT);
      return Optional.empty();
    }
    for (Object key : map.keySet()) {
      if (!PLACEMENT_KEYS.contains(String.valueOf(key))) {
        errors.add("placement." + key, Constraint.UNKNOWN_PROPERTY);
      }
    }
    UUID legalEntity = required(map.get("legalEntityId"), "placement.legalEntityId", errors);
    UUID site = required(map.get("siteId"), "placement.siteId", errors);
    UUID department = optional(map.get("departmentId"), "placement.departmentId", errors);
    UUID costCenter = optional(map.get("costCenterId"), "placement.costCenterId", errors);
    UUID team = optional(map.get("teamId"), "placement.teamId", errors);
    if (legalEntity == null || site == null || !errors.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new AssignmentValue.Placement(legalEntity, site, department, costCenter, team));
  }

  private static Optional<Optional<AssignmentValue>> manager(Object raw, FieldErrors errors) {
    if (!(raw instanceof Map<?, ?> map)) {
      errors.add("manager", Constraint.FORMAT);
      return Optional.empty();
    }
    for (Object key : map.keySet()) {
      if (!"employeeId".equals(key)) {
        errors.add("manager." + key, Constraint.UNKNOWN_PROPERTY);
      }
    }
    if (!map.containsKey("employeeId")) {
      errors.add("manager.employeeId", Constraint.REQUIRED);
      return Optional.empty();
    }
    Object id = map.get("employeeId");
    if (id == null) {
      return Optional.of(Optional.empty());
    }
    UUID employee = uuid(id, "manager.employeeId", errors);
    return employee == null
        ? Optional.empty()
        : Optional.of(Optional.of(new AssignmentValue.Manager(employee)));
  }

  private static UUID required(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    return uuid(raw, field, errors);
  }

  private static UUID optional(Object raw, String field, FieldErrors errors) {
    return raw == null ? null : uuid(raw, field, errors);
  }
}

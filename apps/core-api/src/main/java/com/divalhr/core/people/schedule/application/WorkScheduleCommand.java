package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.schedule.api.PublishWorkScheduleRequest;
import com.divalhr.core.people.schedule.api.WorkScheduleRequest;
import com.divalhr.core.people.schedule.domain.SlotDraft;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A validated, normalized schedule proposal (MVP-050A, D50A-2 to D50A-4): the site, the inclusive
 * dates and the slots in submitted order. Only the shape is checked here; the business rules
 * (period, site, conversion, eligibility, conflicts) are the planner's.
 *
 * @param siteId site
 * @param firstDate first date
 * @param lastDate last date
 * @param slots normalized slots, positions 1 to n
 */
public record WorkScheduleCommand(
    UUID siteId, LocalDate firstDate, LocalDate lastDate, List<SlotDraft> slots) {

  /** Most slots in one publication. */
  public static final int MAX_SLOTS = 500;

  private static final LocalDate DATE_MIN = LocalDate.of(1900, 1, 1);
  private static final LocalDate DATE_MAX = LocalDate.of(2999, 12, 31);
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
  private static final DateTimeFormatter LOCAL =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm").withResolverStyle(ResolverStyle.STRICT);
  private static final Pattern DATE_SHAPE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
  private static final Pattern LOCAL_SHAPE =
      Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}$");
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern CLIENT_REF = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");
  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
  private static final Set<String> SLOT_KEYS =
      Set.of("clientRef", "start", "end", "labels", "employeeId");
  private static final Set<String> LABEL_KEYS = Set.of("en", "fr");

  /** Defensively copies the slots. */
  public WorkScheduleCommand {
    slots = List.copyOf(slots);
  }

  /**
   * Identifiers and counts only: dates, labels and employees never reach a log line.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "WorkScheduleCommand[siteId=" + siteId + ", slots=" + slots.size() + "]";
  }

  /**
   * A publication: the proposal, the reviewed preview's digest and the confirmation.
   *
   * @param command the proposal
   * @param previewDigest the preview's digest
   */
  public record Publication(WorkScheduleCommand command, String previewDigest) {

    @Override
    public String toString() {
      return "Publication[" + command + "]";
    }

    /**
     * The normalized publication and its scope, fingerprinted for idempotency.
     *
     * @param tenant verified tenant
     * @return key-sorted canonical form
     */
    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = command.canonical(tenant);
      canonical.put("previewDigest", previewDigest);
      canonical.put("confirm", true);
      return canonical;
    }
  }

  /**
   * Validates a preview request.
   *
   * @param request body
   * @return the command
   * @throws ApiException {@code VALIDATION_FAILED} listing every problem, or {@code
   *     WORK_SCHEDULE_LIMIT_EXCEEDED} above 500 slots
   */
  static WorkScheduleCommand preview(WorkScheduleRequest request) {
    FieldErrors errors = new FieldErrors();
    WorkScheduleCommand command = parse(request, errors);
    errors.throwIfAny();
    return command;
  }

  /**
   * Validates a publication request.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the publication
   * @throws ApiException {@code VALIDATION_FAILED} listing every problem, or {@code
   *     WORK_SCHEDULE_LIMIT_EXCEEDED} above 500 slots
   */
  static Publication publication(String idempotencyKey, PublishWorkScheduleRequest request) {
    FieldErrors errors = new FieldErrors();
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
    WorkScheduleCommand command = parse(request, errors);
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
    return new Publication(command, digest);
  }

  private static WorkScheduleCommand parse(WorkScheduleRequest request, FieldErrors errors) {
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    UUID site = uuid(errors, "siteId", request.getSiteId(), true);
    LocalDate first = date(errors, "firstDate", request.getFirstDate());
    LocalDate last = date(errors, "lastDate", request.getLastDate());
    List<SlotDraft> slots = slots(errors, request.getSlots());
    return new WorkScheduleCommand(site, first, last, slots);
  }

  private static List<SlotDraft> slots(FieldErrors errors, Object raw) {
    if (raw == null) {
      errors.add("slots", Constraint.REQUIRED);
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      errors.add("slots", Constraint.FORMAT);
      return List.of();
    }
    if (list.isEmpty()) {
      errors.add("slots", Constraint.REQUIRED);
      return List.of();
    }
    if (list.size() > MAX_SLOTS) {
      // Bounded before any item is read: no partial publication.
      throw new ApiException(
          ErrorCode.WORK_SCHEDULE_LIMIT_EXCEEDED, Map.of("maximum", Integer.toString(MAX_SLOTS)));
    }
    List<SlotDraft> drafts = new ArrayList<>(list.size());
    Set<String> references = new HashSet<>();
    for (int i = 0; i < list.size(); i++) {
      String field = "slots[" + i + "]";
      if (!(list.get(i) instanceof Map<?, ?> item)) {
        errors.add(field, Constraint.FORMAT);
        continue;
      }
      if (!SLOT_KEYS.containsAll(keys(item))) {
        errors.add(field, Constraint.UNKNOWN_PROPERTY);
      }
      String reference = null;
      Object rawReference = item.get("clientRef");
      if (rawReference == null) {
        errors.add(field + ".clientRef", Constraint.REQUIRED);
      } else if (!(rawReference instanceof String text) || !CLIENT_REF.matcher(text).matches()) {
        errors.add(field + ".clientRef", Constraint.FORMAT);
      } else if (!references.add(text)) {
        errors.add(field + ".clientRef", Constraint.DUPLICATE);
      } else {
        reference = text;
      }
      LocalDateTime start = local(errors, field + ".start", item.get("start"));
      LocalDateTime end = local(errors, field + ".end", item.get("end"));
      String en = null;
      String fr = null;
      Object rawLabels = item.get("labels");
      if (rawLabels == null) {
        errors.add(field + ".labels", Constraint.REQUIRED);
      } else if (!(rawLabels instanceof Map<?, ?> labels)) {
        errors.add(field + ".labels", Constraint.FORMAT);
      } else {
        if (!LABEL_KEYS.containsAll(keys(labels))) {
          errors.add(field + ".labels", Constraint.UNKNOWN_PROPERTY);
        }
        en = label(errors, field + ".labels.en", labels.get("en"));
        fr = label(errors, field + ".labels.fr", labels.get("fr"));
      }
      UUID employee = uuid(errors, field + ".employeeId", item.get("employeeId"), false);
      if (reference != null && start != null && end != null && en != null && fr != null) {
        drafts.add(new SlotDraft(reference, i + 1, start, end, en, fr, employee));
      }
    }
    return drafts;
  }

  private static Set<String> keys(Map<?, ?> map) {
    Set<String> keys = new HashSet<>();
    map.keySet().forEach(key -> keys.add(String.valueOf(key)));
    return keys;
  }

  private static String label(FieldErrors errors, String field, Object raw) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    String label = WorkScheduleLabelGrammar.normalize(raw);
    if (label == null) {
      errors.add(field, Constraint.FORMAT);
    }
    return label;
  }

  private static UUID uuid(FieldErrors errors, String field, Object raw, boolean required) {
    if (raw == null) {
      if (required) {
        errors.add(field, Constraint.REQUIRED);
      }
      return null;
    }
    if (!(raw instanceof String text) || !UUID_SHAPE.matcher(text).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return UUID.fromString(text.toLowerCase(Locale.ROOT));
  }

  /**
   * Parses an optional {@code YYYY-MM-DD} query value.
   *
   * @param errors collector
   * @param field contract name
   * @param raw value or {@code null}
   * @return the date, or {@code null} when absent or invalid
   */
  static LocalDate optionalDate(FieldErrors errors, String field, String raw) {
    return raw == null || raw.isEmpty() ? null : date(errors, field, raw);
  }

  /**
   * Parses an optional UUID query value.
   *
   * @param errors collector
   * @param field contract name
   * @param raw value or {@code null}
   * @return the ID, or {@code null} when absent or invalid
   */
  static UUID optionalUuid(FieldErrors errors, String field, String raw) {
    return raw == null || raw.isEmpty() ? null : uuid(errors, field, raw, false);
  }

  private static LocalDate date(FieldErrors errors, String field, Object raw) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !DATE_SHAPE.matcher(text).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    LocalDate date;
    try {
      date = LocalDate.parse(text, DATE);
    } catch (DateTimeParseException malformed) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    if (date.isBefore(DATE_MIN) || date.isAfter(DATE_MAX)) {
      errors.add(field, Constraint.RANGE);
      return null;
    }
    return date;
  }

  private static LocalDateTime local(FieldErrors errors, String field, Object raw) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !LOCAL_SHAPE.matcher(text).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    LocalDateTime value;
    try {
      value = LocalDateTime.parse(text, LOCAL);
    } catch (DateTimeParseException malformed) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    if (value.toLocalDate().isBefore(DATE_MIN) || value.toLocalDate().isAfter(DATE_MAX)) {
      errors.add(field, Constraint.RANGE);
      return null;
    }
    return value;
  }

  /**
   * The local date-time in its wire form.
   *
   * @param value local date-time
   * @return {@code YYYY-MM-DDTHH:MM}
   */
  public static String wire(LocalDateTime value) {
    return value.format(LOCAL);
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
    canonical.put("siteId", siteId.toString());
    canonical.put("firstDate", firstDate.toString());
    canonical.put("lastDate", lastDate.toString());
    List<Map<String, Object>> items = new ArrayList<>(slots.size());
    for (SlotDraft slot : slots) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("clientRef", slot.clientRef());
      item.put("start", wire(slot.start()));
      item.put("end", wire(slot.end()));
      item.put("labelEn", slot.labelEn());
      item.put("labelFr", slot.labelFr());
      item.put("employeeId", slot.employeeId() == null ? "" : slot.employeeId().toString());
      items.add(item);
    }
    canonical.put("slots", items);
    return canonical;
  }
}

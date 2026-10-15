package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.schedule.api.GenerateTimesheetsRequest;
import com.divalhr.core.people.schedule.api.RefreshTimesheetsRequest;
import com.divalhr.core.people.schedule.api.TimesheetPeriodRequest;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * MVP-070A request validation (T70A-2, T70A-6, T70A-7): the site, the site-local Monday, the
 * confirmation, and for a refresh the reviewed version and preview digest. A malformed site or
 * timesheet is the same {@code 404 TIMESHEET_TARGET_NOT_FOUND} as an unknown one, after every shape
 * check. Whether the Monday is in the past or the current week is decided against the site's clock
 * by the service, never by the browser.
 */
public final class TimesheetCommand {

  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
  private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 3);

  private TimesheetCommand() {}

  /**
   * A site and a site-local Monday.
   *
   * @param siteId site
   * @param weekStart the Monday
   */
  public record Target(UUID siteId, LocalDate weekStart) {

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("siteId", siteId.toString());
      canonical.put("weekStart", weekStart.toString());
      return canonical;
    }
  }

  /**
   * A validated refresh commit.
   *
   * @param target the site and week
   * @param version the reviewed generation version
   * @param previewDigest the reviewed preview digest
   */
  public record Refresh(Target target, int version, String previewDigest) {

    Map<String, Object> canonical(TenantId tenant, UUID generationId) {
      Map<String, Object> canonical = target.canonical(tenant);
      canonical.put("generationId", generationId.toString());
      canonical.put("version", version);
      canonical.put("previewDigest", previewDigest);
      canonical.put("confirm", true);
      return canonical;
    }

    @Override
    public String toString() {
      return "TimesheetCommand.Refresh[version=" + version + "]";
    }
  }

  /**
   * Validates a preview's site and week.
   *
   * @param request body
   * @return the target
   */
  static Target target(TimesheetPeriodRequest request) {
    FieldErrors errors = new FieldErrors();
    Target target = target(request, errors);
    errors.throwIfAny();
    return found(target);
  }

  /**
   * Validates a generation command.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the target
   */
  static Target generate(String idempotencyKey, GenerateTimesheetsRequest request) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    Target target = target(request, errors);
    confirm(request.getConfirm(), errors);
    errors.throwIfAny();
    return found(target);
  }

  /**
   * Validates a refresh commit.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the refresh
   */
  static Refresh refresh(String idempotencyKey, RefreshTimesheetsRequest request) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    Target target = target(request, errors);
    Integer version = null;
    Object raw = request.getVersion();
    if (raw == null) {
      errors.add("version", Constraint.REQUIRED);
    } else if (!(raw instanceof Integer value)) {
      errors.add("version", Constraint.FORMAT);
    } else if (value < 1) {
      errors.add("version", Constraint.RANGE);
    } else {
      version = value;
    }
    String digest = null;
    Object rawDigest = request.getPreviewDigest();
    if (rawDigest == null) {
      errors.add("previewDigest", Constraint.REQUIRED);
    } else if (!(rawDigest instanceof String text) || !DIGEST.matcher(text).matches()) {
      errors.add("previewDigest", Constraint.FORMAT);
    } else {
      digest = text;
    }
    confirm(request.getConfirm(), errors);
    errors.throwIfAny();
    return new Refresh(found(target), version, digest);
  }

  /**
   * A path identifier, or the one safe 404.
   *
   * @param raw path value
   * @return the ID
   */
  static UUID id(String raw) {
    if (raw == null || !UUID_SHAPE.matcher(raw).matches()) {
      throw notFound();
    }
    return UUID.fromString(raw.toLowerCase(Locale.ROOT));
  }

  static ApiException notFound() {
    return new ApiException(ErrorCode.TIMESHEET_TARGET_NOT_FOUND, Map.of());
  }

  // ------------------------------------------------------------------------------------------

  static Target target(TimesheetPeriodRequest request, FieldErrors errors) {
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    UUID site = null;
    if (request.getSiteId() == null) {
      errors.add("siteId", Constraint.REQUIRED);
    } else if (request.getSiteId() instanceof String text && UUID_SHAPE.matcher(text).matches()) {
      site = UUID.fromString(text.toLowerCase(Locale.ROOT));
    }
    LocalDate week = null;
    Object rawWeek = request.getWeekStart();
    if (rawWeek == null) {
      errors.add("weekStart", Constraint.REQUIRED);
    } else if (!(rawWeek instanceof String text) || !DATE.matcher(text).matches()) {
      errors.add("weekStart", Constraint.FORMAT);
    } else {
      try {
        week = LocalDate.parse(text);
        if (week.getDayOfWeek() != DayOfWeek.MONDAY || week.isBefore(EARLIEST)) {
          errors.add("weekStart", Constraint.RANGE);
          week = null;
        }
      } catch (DateTimeParseException invalid) {
        errors.add("weekStart", Constraint.FORMAT);
      }
    }
    // A malformed site is decided after every shape check (the same 404 as an unknown one).
    return new Target(site, week);
  }

  private static Target found(Target target) {
    if (target.siteId() == null) {
      throw notFound();
    }
    return target;
  }

  static void confirm(Object raw, FieldErrors errors) {
    if (!Boolean.TRUE.equals(raw)) {
      errors.add("confirm", Constraint.REQUIRED);
    }
  }

  static void key(String idempotencyKey, FieldErrors errors) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
  }
}

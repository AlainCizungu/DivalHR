package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.schedule.api.CreateTimesheetExportRequest;
import com.divalhr.core.people.schedule.api.TimesheetExportPreviewRequest;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
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
 * MVP-070E request validation (T70E-2, T70E-3, T70E-6): the legal entity, its site, the site-local
 * Monday, and for a creation the reviewed preview digest and the confirmation; the history's
 * optional site and week filters. A malformed legal entity or site is the same {@code 404
 * TIMESHEET_TARGET_NOT_FOUND} as an unknown one, after every shape check. Nothing about tenant,
 * eligibility or boundaries is ever taken from the request.
 */
public final class TimesheetExportCommand {

  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

  private TimesheetExportCommand() {}

  /**
   * A legal entity, one of its sites and a site-local Monday.
   *
   * @param legalEntityId legal entity
   * @param siteId site
   * @param weekStart the Monday
   */
  public record Target(UUID legalEntityId, UUID siteId, LocalDate weekStart) {

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("legalEntityId", legalEntityId.toString());
      canonical.put("siteId", siteId.toString());
      canonical.put("weekStart", weekStart.toString());
      return canonical;
    }
  }

  /**
   * A validated creation.
   *
   * @param target the scope
   * @param previewDigest the reviewed preview digest
   */
  public record Create(Target target, String previewDigest) {

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = target.canonical(tenant);
      canonical.put("previewDigest", previewDigest);
      canonical.put("confirm", true);
      return canonical;
    }

    @Override
    public String toString() {
      return "TimesheetExportCommand.Create[week=" + target.weekStart() + "]";
    }
  }

  /**
   * The history's optional filters.
   *
   * @param siteId site, or {@code null}
   * @param weekStart local Monday, or {@code null}
   */
  public record Filter(UUID siteId, LocalDate weekStart) {}

  /**
   * Validates a preview's scope.
   *
   * @param request body
   * @return the target
   */
  static Target target(TimesheetExportPreviewRequest request) {
    FieldErrors errors = new FieldErrors();
    Target target = target(request, errors);
    errors.throwIfAny();
    return found(target);
  }

  /**
   * Validates a creation.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the creation
   */
  static Create create(String idempotencyKey, CreateTimesheetExportRequest request) {
    FieldErrors errors = new FieldErrors();
    TimesheetCommand.key(idempotencyKey, errors);
    Target target = target(request, errors);
    String digest = null;
    Object raw = request.getPreviewDigest();
    if (raw == null) {
      errors.add("previewDigest", Constraint.REQUIRED);
    } else if (!(raw instanceof String text) || !DIGEST.matcher(text).matches()) {
      errors.add("previewDigest", Constraint.FORMAT);
    } else {
      digest = text;
    }
    TimesheetCommand.confirm(request.getConfirm(), errors);
    errors.throwIfAny();
    return new Create(found(target), digest);
  }

  /**
   * Validates the history's filters.
   *
   * @param siteId raw site filter, or {@code null}
   * @param weekStart raw week filter, or {@code null}
   * @param errors collected errors
   * @return the filter
   */
  static Filter filter(String siteId, String weekStart, FieldErrors errors) {
    UUID site = null;
    if (siteId != null) {
      if (UUID_SHAPE.matcher(siteId).matches()) {
        site = UUID.fromString(siteId.toLowerCase(Locale.ROOT));
      } else {
        errors.add("siteId", Constraint.FORMAT);
      }
    }
    LocalDate week = null;
    if (weekStart != null) {
      week = monday(weekStart, "weekStart", errors);
    }
    return new Filter(site, week);
  }

  // ------------------------------------------------------------------------------------------

  private static Target target(TimesheetExportPreviewRequest request, FieldErrors errors) {
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    TimesheetCommand.Target period = TimesheetCommand.target(request, errors);
    UUID legalEntity = null;
    if (request.getLegalEntityId() == null) {
      errors.add("legalEntityId", Constraint.REQUIRED);
    } else if (request.getLegalEntityId() instanceof String text
        && UUID_SHAPE.matcher(text).matches()) {
      legalEntity = UUID.fromString(text.toLowerCase(Locale.ROOT));
    }
    // A malformed legal entity or site is decided after every shape check (the same 404).
    return new Target(legalEntity, period.siteId(), period.weekStart());
  }

  private static Target found(Target target) {
    if (target.legalEntityId() == null || target.siteId() == null) {
      throw TimesheetCommand.notFound();
    }
    return target;
  }

  private static LocalDate monday(String text, String field, FieldErrors errors) {
    if (!DATE.matcher(text).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    try {
      LocalDate day = LocalDate.parse(text);
      if (day.getDayOfWeek() != DayOfWeek.MONDAY) {
        errors.add(field, Constraint.RANGE);
        return null;
      }
      return day;
    } catch (DateTimeParseException invalid) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
  }
}

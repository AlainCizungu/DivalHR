package com.divalhr.core.tenant.application;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.pagination.PageRequest;
import com.divalhr.core.tenant.api.AssignSiteRegionRequest;
import com.divalhr.core.tenant.api.CreateLegalEntityRequest;
import com.divalhr.core.tenant.api.CreateRegionRequest;
import com.divalhr.core.tenant.api.CreateSiteRequest;
import com.divalhr.core.tenant.api.CreateTeamRequest;
import com.divalhr.core.tenant.api.SiteUnitRequest;
import com.divalhr.core.tenant.api.StrictRequest;
import com.divalhr.core.tenant.domain.EffectivePeriod;
import com.divalhr.core.tenant.domain.HierarchyCode;
import com.divalhr.core.tenant.domain.SupportedConfiguration;
import com.divalhr.core.tenant.domain.TeamParentKind;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates hierarchy requests into stable codes (approved precedence, Issue #12): every format
 * problem first ({@code VALIDATION_FAILED}), then {@code EFFECTIVE_DATE_INVALID}, then support
 * checks. Checks that need the parent (existence, time zone for its country, containment) run in
 * the transaction. Submitted values never appear in error parameters.
 */
@Component
public class HierarchyValidator {

  private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
  private static final Pattern ISO_DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
  private static final Set<String> IANA_ZONES = Set.copyOf(ZoneId.getAvailableZoneIds());

  /**
   * Validates a legal-entity create.
   *
   * @param idempotencyKey header value
   * @param request body
   * @return normalized command
   */
  public LegalEntityCommand legalEntity(String idempotencyKey, CreateLegalEntityRequest request) {
    FieldErrors errors = new FieldErrors();
    key(errors, idempotencyKey);
    unknown(errors, request);
    String code = code(errors, request.getCode());
    String name = name(errors, request.getName());
    String country = request.getCountryCode();
    if (country == null || country.isEmpty()) {
      errors.add("countryCode", Constraint.REQUIRED);
    } else if (!COUNTRY.matcher(country).matches()) {
      errors.add("countryCode", Constraint.FORMAT);
    }
    LocalDate from = date(errors, "effectiveFrom", request.getEffectiveFrom(), true);
    LocalDate to = date(errors, "effectiveTo", request.getEffectiveTo(), false);
    errors.throwIfAny();
    EffectivePeriod period = period(from, to);
    if (SupportedConfiguration.country(country).isEmpty()) {
      throw new ApiException(
          ErrorCode.COUNTRY_NOT_SUPPORTED,
          Map.of(
              "field",
              "countryCode",
              "supported",
              SupportedConfiguration.COUNTRIES.keySet().stream().sorted().toList()));
    }
    return new LegalEntityCommand(code, name, country, period);
  }

  /**
   * Validates a site create (format and date order only; parent-dependent rules run later).
   *
   * @param idempotencyKey header value
   * @param request body
   * @return normalized command
   */
  public SiteCommand site(String idempotencyKey, CreateSiteRequest request) {
    FieldErrors errors = new FieldErrors();
    key(errors, idempotencyKey);
    unknown(errors, request);
    UUID legalEntityId = uuid(errors, "legalEntityId", request.getLegalEntityId());
    // Optional: absent (or null) keeps the pre-region validation exactly; present must be a UUID.
    UUID regionId = optionalUuid(errors, "regionId", request.getRegionId());
    String code = code(errors, request.getCode());
    String name = name(errors, request.getName());
    String timezone = request.getTimezone();
    if (timezone == null || timezone.isEmpty()) {
      errors.add("timezone", Constraint.REQUIRED);
    } else if (timezone.length() > 64 || !IANA_ZONES.contains(timezone)) {
      errors.add("timezone", Constraint.FORMAT);
    }
    LocalDate from = date(errors, "effectiveFrom", request.getEffectiveFrom(), true);
    LocalDate to = date(errors, "effectiveTo", request.getEffectiveTo(), false);
    errors.throwIfAny();
    return new SiteCommand(legalEntityId, regionId, code, name, timezone, period(from, to));
  }

  /**
   * Validates a region creation request without consulting the parent.
   *
   * @param idempotencyKey header value
   * @param request body
   * @return normalized command
   */
  public RegionCommand region(String idempotencyKey, CreateRegionRequest request) {
    FieldErrors errors = new FieldErrors();
    key(errors, idempotencyKey);
    unknown(errors, request);
    UUID legalEntityId = uuid(errors, "legalEntityId", request.getLegalEntityId());
    String code = code(errors, request.getCode());
    String name = name(errors, request.getName());
    LocalDate from = date(errors, "effectiveFrom", request.getEffectiveFrom(), true);
    LocalDate to = date(errors, "effectiveTo", request.getEffectiveTo(), false);
    errors.throwIfAny();
    return new RegionCommand(legalEntityId, code, name, period(from, to));
  }

  /**
   * Validates a team creation request without consulting the parent. Order: every field defect
   * together ({@code VALIDATION_FAILED}, including a malformed supplied parent id), then parent
   * cardinality ({@code TEAM_PARENT_AMBIGUOUS} / {@code TEAM_PARENT_REQUIRED}), then date order. An
   * absent parent property and an explicit {@code null} both mean "not supplied".
   *
   * @param idempotencyKey header value
   * @param request body
   * @return normalized command
   */
  public TeamCommand team(String idempotencyKey, CreateTeamRequest request) {
    FieldErrors errors = new FieldErrors();
    key(errors, idempotencyKey);
    unknown(errors, request);
    UUID department = optionalUuid(errors, "departmentId", request.getDepartmentId());
    UUID costCenter = optionalUuid(errors, "costCenterId", request.getCostCenterId());
    String code = code(errors, request.getCode());
    String name = name(errors, request.getName());
    LocalDate from = date(errors, "effectiveFrom", request.getEffectiveFrom(), true);
    LocalDate to = date(errors, "effectiveTo", request.getEffectiveTo(), false);
    errors.throwIfAny();
    TeamParentRef parent = exactlyOneParent(department, costCenter);
    return new TeamCommand(parent.kind(), parent.id(), code, name, period(from, to));
  }

  /**
   * Validates the parameters of the team list. An absent or empty query parameter is not supplied.
   * Format and limit first, then parent cardinality.
   *
   * @param departmentId raw department filter
   * @param costCenterId raw cost-center filter
   * @param limit raw limit
   * @return the single parent filter
   */
  public TeamParentRef teamListParent(String departmentId, String costCenterId, String limit) {
    FieldErrors errors = new FieldErrors();
    String rawDepartment = departmentId == null || departmentId.isEmpty() ? null : departmentId;
    String rawCostCenter = costCenterId == null || costCenterId.isEmpty() ? null : costCenterId;
    UUID department = optionalUuid(errors, "departmentId", rawDepartment);
    UUID costCenter = optionalUuid(errors, "costCenterId", rawCostCenter);
    if (PageRequest.parse(limit) == null) {
      errors.add("limit", Constraint.RANGE);
    }
    errors.throwIfAny();
    return exactlyOneParent(department, costCenter);
  }

  /** Called after format validation: a supplied parent id is non-null here. */
  private static TeamParentRef exactlyOneParent(UUID department, UUID costCenter) {
    if (department != null && costCenter != null) {
      throw new ApiException(ErrorCode.TEAM_PARENT_AMBIGUOUS, Map.of());
    }
    if (department != null) {
      return new TeamParentRef(TeamParentKind.DEPARTMENT, department);
    }
    if (costCenter != null) {
      return new TeamParentRef(TeamParentKind.COST_CENTER, costCenter);
    }
    throw new ApiException(ErrorCode.TEAM_PARENT_REQUIRED, Map.of());
  }

  /**
   * Validates the parameters of the region list.
   *
   * @param legalEntityId required parent
   * @param limit optional page size
   * @return parent id
   */
  public UUID regionListParent(String legalEntityId, String limit) {
    return siteListParent(legalEntityId, limit);
  }

  /**
   * Validates a site-region assignment: the path's site id, the key and the strict body.
   *
   * @param siteId path value
   * @param idempotencyKey header value
   * @param request body
   * @return normalized command
   */
  public SiteRegionCommand siteRegion(
      String siteId, String idempotencyKey, AssignSiteRegionRequest request) {
    FieldErrors errors = new FieldErrors();
    UUID site = uuid(errors, "siteId", siteId);
    key(errors, idempotencyKey);
    unknown(errors, request);
    UUID region = uuid(errors, "regionId", request.getRegionId());
    errors.throwIfAny();
    return new SiteRegionCommand(site, region);
  }

  /**
   * Validates a department or cost-center create (format and date order only; the parent site,
   * containment and uniqueness are checked in the transaction).
   *
   * @param idempotencyKey header value
   * @param request body
   * @return normalized command
   */
  public SiteUnitCommand siteUnit(String idempotencyKey, SiteUnitRequest request) {
    FieldErrors errors = new FieldErrors();
    key(errors, idempotencyKey);
    unknown(errors, request);
    UUID siteId = uuid(errors, "siteId", request.getSiteId());
    String code = code(errors, request.getCode());
    String name = name(errors, request.getName());
    LocalDate from = date(errors, "effectiveFrom", request.getEffectiveFrom(), true);
    LocalDate to = date(errors, "effectiveTo", request.getEffectiveTo(), false);
    errors.throwIfAny();
    return new SiteUnitCommand(siteId, code, name, period(from, to));
  }

  /**
   * Validates the department or cost-center list parent and limit together.
   *
   * @param siteId raw parent id
   * @param limit raw limit
   * @return parsed site id
   */
  public UUID siteUnitListParent(String siteId, String limit) {
    FieldErrors errors = new FieldErrors();
    UUID parent = uuid(errors, "siteId", siteId);
    if (PageRequest.parse(limit) == null) {
      errors.add("limit", Constraint.RANGE);
    }
    errors.throwIfAny();
    return parent;
  }

  /**
   * Validates list parameters.
   *
   * @param limit raw limit
   * @return page request
   */
  public PageRequest page(String limit) {
    PageRequest page = PageRequest.parse(limit);
    if (page == null) {
      new FieldErrors().add("limit", Constraint.RANGE).throwIfAny();
    }
    return page;
  }

  /**
   * Validates the site-list parent and limit together.
   *
   * @param legalEntityId raw parent id
   * @param limit raw limit
   * @return parsed parent id
   */
  public UUID siteListParent(String legalEntityId, String limit) {
    FieldErrors errors = new FieldErrors();
    UUID parent = uuid(errors, "legalEntityId", legalEntityId);
    if (PageRequest.parse(limit) == null) {
      errors.add("limit", Constraint.RANGE);
    }
    errors.throwIfAny();
    return parent;
  }

  private static EffectivePeriod period(LocalDate from, LocalDate to) {
    if (to != null && to.isBefore(from)) {
      throw new ApiException(ErrorCode.EFFECTIVE_DATE_INVALID, Map.of("field", "effectiveTo"));
    }
    return new EffectivePeriod(from, to);
  }

  private static void key(FieldErrors errors, String key) {
    if (key == null || key.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(key)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
  }

  private static void unknown(FieldErrors errors, StrictRequest request) {
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
  }

  private static String code(FieldErrors errors, String raw) {
    if (raw == null || raw.isBlank()) {
      errors.add("code", Constraint.REQUIRED);
      return null;
    }
    String code = HierarchyCode.normalize(raw);
    if (!HierarchyCode.isValid(code)) {
      errors.add(
          "code", code.length() < 2 || code.length() > 20 ? Constraint.LENGTH : Constraint.FORMAT);
    }
    return code;
  }

  private static String name(FieldErrors errors, String raw) {
    String name = raw == null ? null : raw.strip();
    if (name == null || name.isEmpty()) {
      errors.add("name", Constraint.REQUIRED);
    } else if (CONTROL.matcher(name).find()) {
      errors.add("name", Constraint.FORMAT);
    } else {
      int length = name.codePointCount(0, name.length());
      if (length < 2 || length > 160) {
        errors.add("name", Constraint.LENGTH);
      }
    }
    return name;
  }

  private static UUID uuid(FieldErrors errors, String field, String raw) {
    if (raw == null || raw.isEmpty()) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!UUID_SHAPE.matcher(raw).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return UUID.fromString(raw);
  }

  private static UUID optionalUuid(FieldErrors errors, String field, String raw) {
    if (raw == null) {
      return null;
    }
    if (!UUID_SHAPE.matcher(raw).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return UUID.fromString(raw);
  }

  private static LocalDate date(FieldErrors errors, String field, String raw, boolean required) {
    if (raw == null || raw.isEmpty()) {
      if (required) {
        errors.add(field, Constraint.REQUIRED);
      }
      return null;
    }
    if (!ISO_DATE.matcher(raw).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    LocalDate date;
    try {
      date = LocalDate.parse(raw);
    } catch (DateTimeParseException invalid) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    if (!EffectivePeriod.inSupportedRange(date)) {
      errors.add(field, Constraint.RANGE);
      return null;
    }
    return date;
  }

  /**
   * The approved constraint vocabulary, exposed for contract tests.
   *
   * @return constraint names
   */
  public static List<String> constraints() {
    return java.util.Arrays.stream(Constraint.values()).map(Enum::name).toList();
  }
}

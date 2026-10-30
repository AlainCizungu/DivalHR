package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.ActivateStatutoryRulePackRequest;
import com.divalhr.core.payroll.api.CommitStatutoryCalculationRequest;
import com.divalhr.core.payroll.api.CommitStatutoryProfileRequest;
import com.divalhr.core.payroll.api.StatutoryCalculationCommandRequest;
import com.divalhr.core.payroll.api.StatutoryProfileCommandRequest;
import com.divalhr.core.payroll.domain.PayrollPeriod;
import com.divalhr.core.payroll.domain.StatutoryClassification;
import com.divalhr.core.payroll.domain.StatutoryEvidence;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validation of the statutory preview requests (MVP-094B): shapes first (one field error per
 * problem, every unknown property named), then the stable domain errors the services raise under
 * the locks. Path identifiers that are malformed are the same safe 404 as unknown ones (see {@link
 * PayrollCommands#id}). Restricted financial and HR data: no record here prints a flag, a
 * classification or a digest.
 */
final class StatutoryCommands {

  /** Largest page of rule packs and of calculations. */
  static final int MAX_LIST_LIMIT = 100;

  /** Default page of rule packs and of calculations. */
  static final int DEFAULT_LIST_LIMIT = 25;

  /** Largest page of lines of a calculation. */
  static final int MAX_LINE_LIMIT = 200;

  /** Default page of lines of a calculation. */
  static final int DEFAULT_LINE_LIMIT = 50;

  private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  private StatutoryCommands() {}

  /**
   * A well-formed activation command.
   *
   * @param packId the pack to activate
   * @param fromMonth the first payroll month
   * @param expectedVersion the number of activations the read showed
   */
  record Activation(UUID packId, PayrollPeriod fromMonth, int expectedVersion) {

    Map<String, Object> canonical(TenantId tenant, UUID legalEntityId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("legalEntityId", legalEntityId.toString());
      canonical.put("packId", packId.toString());
      canonical.put("fromMonth", fromMonth.text());
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("acknowledged", true);
      return canonical;
    }
  }

  /**
   * A well-formed profile entry command.
   *
   * @param effectiveFrom the first day in force
   * @param classification the classification
   * @param cnssSubject whether subject to CNSS
   * @param irppSubject whether subject to the IRPP salary withholding
   * @param evidence the evidence code
   */
  record ProfileDraft(
      LocalDate effectiveFrom,
      StatutoryClassification classification,
      boolean cnssSubject,
      boolean irppSubject,
      StatutoryEvidence evidence) {

    Map<String, Object> canonical(Map<String, Object> into) {
      into.put("effectiveFrom", effectiveFrom.toString());
      into.put("classification", classification.name());
      into.put("cnssSubject", cnssSubject);
      into.put("irppSubject", irppSubject);
      into.put("evidenceCode", evidence.name());
      return into;
    }
  }

  /**
   * A well-formed profile commit.
   *
   * @param draft the entry
   * @param expectedVersion the number of entries the preview showed
   * @param previewDigest the preview digest the preview returned
   * @param expiresAt the expiry the preview returned
   */
  record ProfileCommit(
      ProfileDraft draft, int expectedVersion, String previewDigest, Instant expiresAt) {

    Map<String, Object> canonical(TenantId tenant, UUID employeeId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("employeeId", employeeId.toString());
      draft.canonical(canonical);
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("previewDigest", previewDigest);
      canonical.put("expiresAt", expiresAt.toString());
      canonical.put("confirmed", true);
      return canonical;
    }
  }

  /**
   * A well-formed calculation commit.
   *
   * @param expectedVersion the chain version the preview showed
   * @param sourceRunDigest the source run digest the preview returned
   * @param profileDigest the profile digest the preview returned
   * @param rulePackDigest the rule pack digest the preview returned
   * @param calculationDigest the calculation digest the preview returned
   * @param expiresAt the expiry the preview returned
   */
  record CalculationCommit(
      int expectedVersion,
      String sourceRunDigest,
      String profileDigest,
      String rulePackDigest,
      String calculationDigest,
      Instant expiresAt) {

    Map<String, Object> canonical(TenantId tenant, UUID legalEntityId, UUID runId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("legalEntityId", legalEntityId.toString());
      canonical.put("runId", runId.toString());
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("sourceRunDigest", sourceRunDigest);
      canonical.put("profileDigest", profileDigest);
      canonical.put("rulePackDigest", rulePackDigest);
      canonical.put("calculationDigest", calculationDigest);
      canonical.put("expiresAt", expiresAt.toString());
      canonical.put("confirmed", true);
      return canonical;
    }
  }

  // ------------------------------------------------------------------------------------------
  // Entry points
  // ------------------------------------------------------------------------------------------

  /**
   * A pack identifier of a path: malformed is the same answer as unknown.
   *
   * @param raw path value
   * @return the identifier
   */
  static UUID packId(String raw) {
    return PayrollCommands.id(raw, ErrorCode.STATUTORY_RULE_PACK_NOT_FOUND);
  }

  /**
   * The pack status filter of a list.
   *
   * @param raw the raw value, or {@code null}
   * @param errors collector
   * @return {@code DRAFT}, {@code VERIFIED}, {@code RETIRED} or {@code null} for no filter
   */
  static String statusFilter(String raw, FieldErrors errors) {
    if (raw == null) {
      return null;
    }
    if ("DRAFT".equals(raw) || "VERIFIED".equals(raw) || "RETIRED".equals(raw)) {
      return raw;
    }
    errors.add("status", Constraint.FORMAT);
    return null;
  }

  /**
   * The requested month of an activation read.
   *
   * @param raw the raw value, or {@code null}
   * @param errors collector
   * @return the month, or {@code null} after recording the error
   */
  static PayrollPeriod month(String raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("month", Constraint.REQUIRED);
      return null;
    }
    Optional<PayrollPeriod> period = PayrollPeriod.parse(raw);
    if (period.isEmpty()) {
      errors.add("month", Constraint.FORMAT);
      return null;
    }
    return period.get();
  }

  static Activation activation(String idempotencyKey, ActivateStatutoryRulePackRequest request) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    UUID packId = uuid(request.getPackId(), "packId", errors);
    PayrollPeriod fromMonth = month(request.getFromMonth(), "fromMonth", errors);
    Integer version = version(request.getExpectedVersion(), 60, errors);
    acknowledged(request.getAcknowledged(), errors);
    errors.throwIfAny();
    return new Activation(packId, fromMonth, version);
  }

  static ProfileDraft profileDraft(StatutoryProfileCommandRequest request) {
    FieldErrors errors = new FieldErrors();
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    ProfileDraft draft =
        profile(
            request.getEffectiveFrom(),
            request.getClassification(),
            request.getCnssSubject(),
            request.getIrppSubject(),
            request.getEvidenceCode(),
            errors);
    errors.throwIfAny();
    return draft;
  }

  static ProfileCommit profileCommit(String idempotencyKey, CommitStatutoryProfileRequest request) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    ProfileDraft draft =
        profile(
            request.getEffectiveFrom(),
            request.getClassification(),
            request.getCnssSubject(),
            request.getIrppSubject(),
            request.getEvidenceCode(),
            errors);
    Integer version = version(request.getExpectedVersion(), 100, errors);
    String digest = digest(request.getPreviewDigest(), "previewDigest", errors);
    Instant expiresAt = instant(request.getExpiresAt(), "expiresAt", errors);
    confirmed(request.getConfirmed(), errors);
    errors.throwIfAny();
    return new ProfileCommit(draft, version, digest, expiresAt);
  }

  static void calculationCommand(StatutoryCalculationCommandRequest request) {
    FieldErrors errors = new FieldErrors();
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
    } else if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    errors.throwIfAny();
  }

  static CalculationCommit calculationCommit(
      String idempotencyKey, CommitStatutoryCalculationRequest request) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    Integer version = version(request.getExpectedVersion(), 25, errors);
    String source = digest(request.getSourceRunDigest(), "sourceRunDigest", errors);
    String profiles = digest(request.getProfileDigest(), "profileDigest", errors);
    String rulePack = digest(request.getRulePackDigest(), "rulePackDigest", errors);
    String calculation = digest(request.getCalculationDigest(), "calculationDigest", errors);
    Instant expiresAt = instant(request.getExpiresAt(), "expiresAt", errors);
    confirmed(request.getConfirmed(), errors);
    errors.throwIfAny();
    return new CalculationCommit(version, source, profiles, rulePack, calculation, expiresAt);
  }

  // ------------------------------------------------------------------------------------------
  // Parts
  // ------------------------------------------------------------------------------------------

  private static ProfileDraft profile(
      Object rawFrom,
      Object rawClassification,
      Object rawCnss,
      Object rawIrpp,
      Object rawEvidence,
      FieldErrors errors) {
    LocalDate from = date(rawFrom, "effectiveFrom", errors);
    StatutoryClassification classification =
        symbol(StatutoryClassification.class, rawClassification, "classification", errors);
    Boolean cnss = flag(rawCnss, "cnssSubject", errors);
    Boolean irpp = flag(rawIrpp, "irppSubject", errors);
    StatutoryEvidence evidence =
        symbol(StatutoryEvidence.class, rawEvidence, "evidenceCode", errors);
    if (classification == StatutoryClassification.UNSUPPORTED) {
      if (Boolean.TRUE.equals(cnss)) {
        errors.add("cnssSubject", Constraint.FORMAT);
      }
      if (Boolean.TRUE.equals(irpp)) {
        errors.add("irppSubject", Constraint.FORMAT);
      }
    }
    if (from == null
        || classification == null
        || cnss == null
        || irpp == null
        || evidence == null) {
      return null;
    }
    return new ProfileDraft(from, classification, cnss, irpp, evidence);
  }

  private static UUID uuid(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !UUID_SHAPE.matcher(text).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return UUID.fromString(text.toLowerCase(Locale.ROOT));
  }

  private static PayrollPeriod month(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    Optional<PayrollPeriod> period =
        raw instanceof String text ? PayrollPeriod.parse(text) : Optional.empty();
    if (period.isEmpty()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return period.get();
  }

  private static LocalDate date(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !DATE.matcher(text).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException invalid) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
  }

  private static Instant instant(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof String text && text.length() <= 40) {
      try {
        return Instant.parse(text);
      } catch (DateTimeParseException invalid) {
        errors.add(field, Constraint.FORMAT);
        return null;
      }
    }
    errors.add(field, Constraint.FORMAT);
    return null;
  }

  private static Integer version(Object raw, int max, FieldErrors errors) {
    if (raw == null) {
      errors.add("expectedVersion", Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof Integer || raw instanceof Long) {
      long value = ((Number) raw).longValue();
      if (value >= 0 && value <= max) {
        return (int) value;
      }
      errors.add("expectedVersion", Constraint.RANGE);
      return null;
    }
    errors.add("expectedVersion", Constraint.FORMAT);
    return null;
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

  private static Boolean flag(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof Boolean value)) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return value;
  }

  private static <E extends Enum<E>> E symbol(
      Class<E> type, Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof String text) {
      for (E constant : type.getEnumConstants()) {
        if (constant.name().equals(text)) {
          return constant;
        }
      }
    }
    errors.add(field, Constraint.FORMAT);
    return null;
  }

  private static void confirmed(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("confirmed", Constraint.REQUIRED);
    } else if (!(raw instanceof Boolean value)) {
      errors.add("confirmed", Constraint.FORMAT);
    } else if (!value) {
      errors.add("confirmed", Constraint.REQUIRED);
    }
  }

  private static void acknowledged(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("acknowledged", Constraint.REQUIRED);
    } else if (!(raw instanceof Boolean value)) {
      errors.add("acknowledged", Constraint.FORMAT);
    } else if (!value) {
      errors.add("acknowledged", Constraint.REQUIRED);
    }
  }

  private static void key(String idempotencyKey, FieldErrors errors) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
  }
}

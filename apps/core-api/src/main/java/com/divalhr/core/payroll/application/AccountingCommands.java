package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CommitAccountingJournalRequest;
import com.divalhr.core.payroll.api.CommitAccountingMappingRequest;
import com.divalhr.core.payroll.api.PreviewAccountingMappingRequest;
import com.divalhr.core.payroll.domain.AccountingAccounts;
import com.divalhr.core.payroll.domain.AccountingCurrency;
import com.divalhr.core.payroll.domain.AccountingDigests;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Parsing of accounting mapping and journal commands (MVP-096, ADR 0039). Every raw value is
 * validated here, before any state is read; a wrong type, a missing field or an unknown property is
 * a field error that never echoes the value. An account code that is present but not valid is
 * {@code 422 ACCOUNTING_MAPPING_INVALID}, whose only parameter names the property; the code itself
 * is never echoed and never enters a canonical command (only its digest does).
 */
final class AccountingCommands {

  private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
  private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
  private static final LocalDate LATEST = LocalDate.of(2999, 12, 31);

  private AccountingCommands() {}

  /**
   * A well-formed mapping draft (the preview body).
   *
   * @param currency the currency
   * @param effectiveFrom the first day the version applies
   * @param accounts the five trimmed codes
   */
  record MappingDraft(
      AccountingCurrency currency, LocalDate effectiveFrom, AccountingAccounts accounts) {
    @Override
    public String toString() {
      return "AccountingMappingDraft[redacted]";
    }
  }

  /**
   * A well-formed mapping commit.
   *
   * @param draft the previewed currency, date and codes
   * @param expectedVersion the version number the preview announced
   * @param previewDigest the preview digest the preview returned
   * @param expiresAt the expiry the preview returned
   */
  record MappingCommit(
      MappingDraft draft, int expectedVersion, String previewDigest, Instant expiresAt) {

    Map<String, Object> canonical(UUID tenant, UUID legalEntityId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("legalEntityId", legalEntityId.toString());
      canonical.put("currency", draft.currency().name());
      canonical.put("effectiveFrom", draft.effectiveFrom().toString());
      canonical.put(
          "mappingDigest",
          AccountingDigests.mapping(
              tenant,
              legalEntityId,
              draft.currency(),
              expectedVersion,
              draft.effectiveFrom(),
              draft.accounts()));
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("previewDigest", previewDigest);
      canonical.put("expiresAt", expiresAt.toString());
      canonical.put("understandExportOnly", true);
      return canonical;
    }

    @Override
    public String toString() {
      return "AccountingMappingCommit[redacted]";
    }
  }

  /**
   * A well-formed journal commit.
   *
   * @param packageVersion the package version the preview showed
   * @param mappingVersion the mapping version the preview showed
   * @param previewDigest the preview digest the preview returned
   * @param expiresAt the expiry the preview returned
   */
  record JournalCommit(
      int packageVersion, int mappingVersion, String previewDigest, Instant expiresAt) {

    Map<String, Object> canonical(UUID tenant, UUID legalEntityId, UUID packageId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("legalEntityId", legalEntityId.toString());
      canonical.put("packageId", packageId.toString());
      canonical.put("expectedPackageVersion", packageVersion);
      canonical.put("expectedMappingVersion", mappingVersion);
      canonical.put("previewDigest", previewDigest);
      canonical.put("expiresAt", expiresAt.toString());
      canonical.put("understandExportOnly", true);
      return canonical;
    }

    @Override
    public String toString() {
      return "AccountingJournalCommit[redacted]";
    }
  }

  // ------------------------------------------------------------------------------------------
  // Identifiers
  // ------------------------------------------------------------------------------------------

  /** One safe 404 for a malformed, unknown or foreign legal entity of a mapping operation. */
  static UUID mappingEntityId(String raw) {
    return PayrollCommands.id(raw, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
  }

  /** One safe 404 for a malformed, unknown or foreign legal entity or package. */
  static UUID packageScopeId(String raw) {
    return PayrollCommands.id(raw, ErrorCode.PAYROLL_PACKAGE_NOT_FOUND);
  }

  /** One safe 404 for a malformed, unknown or foreign legal entity or journal. */
  static UUID journalScopeId(String raw) {
    return PayrollCommands.id(raw, ErrorCode.ACCOUNTING_JOURNAL_NOT_FOUND);
  }

  static ApiException journalNotFound() {
    return new ApiException(ErrorCode.ACCOUNTING_JOURNAL_NOT_FOUND, Map.of());
  }

  static ApiException packageNotFound() {
    return new ApiException(ErrorCode.PAYROLL_PACKAGE_NOT_FOUND, Map.of());
  }

  /**
   * The currency filter of a mapping list.
   *
   * @param raw the raw value, or {@code null}
   * @param errors collector
   * @return the currency name, or {@code null} for no filter
   */
  static String currencyFilter(String raw, FieldErrors errors) {
    if (raw == null) {
      return null;
    }
    Optional<AccountingCurrency> currency = AccountingCurrency.parse(raw);
    if (currency.isEmpty()) {
      errors.add("currency", Constraint.FORMAT);
      return null;
    }
    return currency.get().name();
  }

  // ------------------------------------------------------------------------------------------
  // Mapping
  // ------------------------------------------------------------------------------------------

  static MappingDraft mappingDraft(PreviewAccountingMappingRequest request) {
    FieldErrors errors = new FieldErrors();
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    return draft(request.getCurrency(), request.getEffectiveFrom(), request.getAccounts(), errors);
  }

  static MappingCommit mappingCommit(
      String idempotencyKey, CommitAccountingMappingRequest request) {
    FieldErrors errors = new FieldErrors();
    PayrollPackageCommands.key(idempotencyKey, errors);
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    Integer version = bounded(request.getExpectedVersion(), "expectedVersion", 10, errors);
    String preview =
        PayrollPackageCommands.digest(request.getPreviewDigest(), "previewDigest", errors);
    Instant expiresAt = PayrollPackageCommands.instant(request.getExpiresAt(), "expiresAt", errors);
    PayrollPackageCommands.confirmed(
        request.getUnderstandExportOnly(), "understandExportOnly", errors);
    MappingDraft draft =
        draft(request.getCurrency(), request.getEffectiveFrom(), request.getAccounts(), errors);
    return new MappingCommit(draft, version, preview, expiresAt);
  }

  /** Shape errors first (400), then the first invalid code (422). */
  private static MappingDraft draft(
      Object rawCurrency, Object rawEffective, Object rawAccounts, FieldErrors errors) {
    AccountingCurrency currency = currency(rawCurrency, errors);
    LocalDate effectiveFrom = effectiveFrom(rawEffective, errors);
    Map<String, String> codes = new HashMap<>();
    if (rawAccounts == null) {
      errors.add("accounts", Constraint.REQUIRED);
    } else if (!(rawAccounts instanceof Map<?, ?> map)) {
      errors.add("accounts", Constraint.FORMAT);
    } else {
      for (Object key : map.keySet()) {
        if (!AccountingAccounts.FIELDS.contains(String.valueOf(key))) {
          errors.add("accounts", Constraint.UNKNOWN_PROPERTY);
          break;
        }
      }
      for (String field : AccountingAccounts.FIELDS) {
        Object value = map.get(field);
        if (value == null) {
          errors.add(field, Constraint.REQUIRED);
        } else if (value instanceof String text) {
          codes.put(field, text);
        } else {
          errors.add(field, Constraint.FORMAT);
        }
      }
    }
    errors.throwIfAny();
    List<String> trimmed = new ArrayList<>();
    for (String field : AccountingAccounts.FIELDS) {
      Optional<String> code = AccountingAccounts.code(codes.get(field));
      if (code.isEmpty()) {
        throw new ApiException(ErrorCode.ACCOUNTING_MAPPING_INVALID, Map.of("field", field));
      }
      trimmed.add(code.get());
    }
    AccountingAccounts accounts =
        new AccountingAccounts(
            trimmed.get(0), trimmed.get(1), trimmed.get(2), trimmed.get(3), trimmed.get(4));
    return new MappingDraft(currency, effectiveFrom, accounts);
  }

  private static AccountingCurrency currency(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("currency", Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof String text) {
      Optional<AccountingCurrency> currency = AccountingCurrency.parse(text);
      if (currency.isPresent()) {
        return currency.get();
      }
    }
    errors.add("currency", Constraint.FORMAT);
    return null;
  }

  private static LocalDate effectiveFrom(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("effectiveFrom", Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !DATE.matcher(text).matches()) {
      errors.add("effectiveFrom", Constraint.FORMAT);
      return null;
    }
    try {
      LocalDate date = LocalDate.parse(text);
      if (date.isBefore(EARLIEST) || date.isAfter(LATEST)) {
        errors.add("effectiveFrom", Constraint.RANGE);
        return null;
      }
      return date;
    } catch (DateTimeParseException invalid) {
      errors.add("effectiveFrom", Constraint.FORMAT);
      return null;
    }
  }

  // ------------------------------------------------------------------------------------------
  // Journal
  // ------------------------------------------------------------------------------------------

  static JournalCommit journalCommit(
      String idempotencyKey, CommitAccountingJournalRequest request) {
    FieldErrors errors = new FieldErrors();
    PayrollPackageCommands.key(idempotencyKey, errors);
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    Integer packageVersion =
        bounded(request.getExpectedPackageVersion(), "expectedPackageVersion", 25, errors);
    Integer mappingVersion =
        bounded(request.getExpectedMappingVersion(), "expectedMappingVersion", 10, errors);
    String preview =
        PayrollPackageCommands.digest(request.getPreviewDigest(), "previewDigest", errors);
    Instant expiresAt = PayrollPackageCommands.instant(request.getExpiresAt(), "expiresAt", errors);
    PayrollPackageCommands.confirmed(
        request.getUnderstandExportOnly(), "understandExportOnly", errors);
    errors.throwIfAny();
    return new JournalCommit(packageVersion, mappingVersion, preview, expiresAt);
  }

  private static Integer bounded(Object raw, String field, int max, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof Integer || raw instanceof Long) {
      long value = ((Number) raw).longValue();
      if (value >= 1 && value <= max) {
        return (int) value;
      }
      errors.add(field, Constraint.RANGE);
      return null;
    }
    errors.add(field, Constraint.FORMAT);
    return null;
  }
}

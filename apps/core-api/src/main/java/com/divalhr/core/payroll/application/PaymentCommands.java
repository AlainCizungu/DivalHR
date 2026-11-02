package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CommitPaymentBatchCancelRequest;
import com.divalhr.core.payroll.api.CommitPaymentBatchRequest;
import com.divalhr.core.payroll.api.CommitPaymentDestinationRequest;
import com.divalhr.core.payroll.api.PaymentBatchCancelCommandRequest;
import com.divalhr.core.payroll.api.PaymentDestinationCommandRequest;
import com.divalhr.core.payroll.api.StrictRequest;
import com.divalhr.core.payroll.domain.PaymentBatchCancelReason;
import com.divalhr.core.payroll.domain.PaymentDestinationDraft;
import com.divalhr.core.payroll.domain.PaymentDestinationReason;
import com.divalhr.core.payroll.domain.PaymentMethod;
import com.divalhr.core.payroll.domain.PaymentProviders;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validation of the payment destination and payment batch requests (MVP-095A): shapes first (one
 * field error per problem, every unknown property named, no value ever echoed), then the stable
 * domain errors the services raise under the locks. Malformed path identifiers are the same safe
 * 404 as unknown ones. Restricted financial and HR data: no record here prints a value, and no
 * canonical map carries a plain hash of a sensitive value (the services add keyed fingerprints).
 */
final class PaymentCommands {

  /** Largest page of destination versions. */
  static final int MAX_HISTORY_LIMIT = 100;

  /** Default page of destination versions. */
  static final int DEFAULT_HISTORY_LIMIT = 25;

  /** Largest page of batches. */
  static final int MAX_BATCH_LIMIT = 100;

  /** Default page of batches. */
  static final int DEFAULT_BATCH_LIMIT = 25;

  /** Largest page of batch lines. */
  static final int MAX_LINE_LIMIT = 200;

  /** Default page of batch lines. */
  static final int DEFAULT_LINE_LIMIT = 25;

  /** Most versions of one employee's destination chain. */
  static final int MAX_DESTINATION_VERSION = 1000;

  /** Earliest effective date of a destination. */
  static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);

  /** Furthest future effective date of a destination, in days from today. */
  static final int HORIZON_DAYS = 90;

  private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
  private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
  private static final Pattern PROVIDER = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
  private static final Pattern ACCOUNT = Pattern.compile("^[A-Z0-9]{6,34}$");
  private static final Pattern BRANCH = Pattern.compile("^[A-Za-z0-9 -]{1,20}$");
  private static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
  private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");

  private PaymentCommands() {}

  /**
   * A well-formed destination commit.
   *
   * @param draft the destination
   * @param expectedVersion the latest version the preview showed
   * @param previewDigest the preview digest the preview returned
   * @param expiresAt the expiry the preview returned
   */
  record DestinationCommit(
      PaymentDestinationDraft draft, int expectedVersion, String previewDigest, Instant expiresAt) {

    Map<String, Object> canonical(
        UUID tenant, UUID employeeId, String contentFingerprint, String explanationFingerprint) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("employeeId", employeeId.toString());
      canonical.put("method", draft.method().name());
      canonical.put("contentFingerprint", contentFingerprint);
      canonical.put("explanationFingerprint", explanationFingerprint);
      canonical.put("effectiveFrom", draft.effectiveFrom().toString());
      canonical.put("reasonCode", draft.reason().name());
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("previewDigest", previewDigest);
      canonical.put("expiresAt", expiresAt.toString());
      canonical.put("reviewedDetails", true);
      return canonical;
    }

    @Override
    public String toString() {
      return "PaymentDestinationCommit[redacted]";
    }
  }

  /**
   * A well-formed batch commit.
   *
   * @param expectedVersion the batch chain version the preview showed
   * @param packageVersion the package version the preview showed
   * @param previewDigest the preview digest the preview returned
   * @param expiresAt the expiry the preview returned
   */
  record BatchCommit(
      int expectedVersion, int packageVersion, String previewDigest, Instant expiresAt) {

    Map<String, Object> canonical(UUID tenant, UUID legalEntityId, UUID packageId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("legalEntityId", legalEntityId.toString());
      canonical.put("packageId", packageId.toString());
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("packageVersion", packageVersion);
      canonical.put("previewDigest", previewDigest);
      canonical.put("expiresAt", expiresAt.toString());
      canonical.put("reviewedTotalsAndDestinations", true);
      canonical.put("noMoneySentAcknowledged", true);
      return canonical;
    }

    @Override
    public String toString() {
      return "PaymentBatchCommit[redacted]";
    }
  }

  /**
   * A well-formed batch cancel commit.
   *
   * @param reason the closed reason
   * @param batchVersion the batch version the preview showed
   * @param batchDigest the batch digest the preview returned
   * @param previewDigest the preview digest the preview returned
   * @param expiresAt the expiry the preview returned
   */
  record CancelCommit(
      PaymentBatchCancelReason reason,
      int batchVersion,
      String batchDigest,
      String previewDigest,
      Instant expiresAt) {

    Map<String, Object> canonical(UUID tenant, UUID legalEntityId, UUID batchId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("legalEntityId", legalEntityId.toString());
      canonical.put("batchId", batchId.toString());
      canonical.put("reasonCode", reason.name());
      canonical.put("batchVersion", batchVersion);
      canonical.put("batchDigest", batchDigest);
      canonical.put("previewDigest", previewDigest);
      canonical.put("expiresAt", expiresAt.toString());
      canonical.put("noMoneySentAcknowledged", true);
      return canonical;
    }

    @Override
    public String toString() {
      return "PaymentCancelCommit[redacted]";
    }
  }

  // ------------------------------------------------------------------------------------------
  // Entry points
  // ------------------------------------------------------------------------------------------

  static UUID employeeId(String raw) {
    return PayrollCommands.id(raw, ErrorCode.PAYMENT_DESTINATION_NOT_FOUND);
  }

  static UUID batchId(String raw) {
    return PayrollCommands.id(raw, ErrorCode.PAYMENT_BATCH_NOT_FOUND);
  }

  /**
   * The optional package filter of a batch list.
   *
   * @param raw raw value, or {@code null}
   * @param errors collector
   * @return the package, or {@code null}
   */
  static UUID packageFilter(String raw, FieldErrors errors) {
    if (raw == null) {
      return null;
    }
    try {
      return PayrollCommands.id(raw, ErrorCode.PAYROLL_PACKAGE_NOT_FOUND);
    } catch (RuntimeException invalid) {
      errors.add("packageId", Constraint.FORMAT);
      return null;
    }
  }

  /**
   * The optional batch status filter.
   *
   * @param raw raw value, or {@code null}
   * @param errors collector
   * @return {@code PREPARED}, {@code CANCELLED} or {@code null}
   */
  static String statusFilter(String raw, FieldErrors errors) {
    if (raw == null) {
      return null;
    }
    if ("PREPARED".equals(raw) || "CANCELLED".equals(raw)) {
      return raw;
    }
    errors.add("status", Constraint.FORMAT);
    return null;
  }

  static PaymentDestinationDraft destinationDraft(
      PaymentDestinationCommandRequest request, LocalDate today) {
    FieldErrors errors = new FieldErrors();
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    unknown(request, errors);
    PaymentDestinationDraft draft =
        draft(
            new Raw(
                request.getMethod(),
                request.getCountryCode(),
                request.getProviderCode(),
                request.getAccountNumber(),
                request.getHolderName(),
                request.getBranchCode(),
                request.getPhoneNumber(),
                request.getEffectiveFrom(),
                request.getReasonCode(),
                request.getExplanation()),
            today,
            errors);
    errors.throwIfAny();
    return draft;
  }

  static DestinationCommit destinationCommit(
      String idempotencyKey, CommitPaymentDestinationRequest request, LocalDate today) {
    FieldErrors errors = new FieldErrors();
    PayrollPackageCommands.key(idempotencyKey, errors);
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    unknown(request, errors);
    PaymentDestinationDraft draft =
        draft(
            new Raw(
                request.getMethod(),
                request.getCountryCode(),
                request.getProviderCode(),
                request.getAccountNumber(),
                request.getHolderName(),
                request.getBranchCode(),
                request.getPhoneNumber(),
                request.getEffectiveFrom(),
                request.getReasonCode(),
                request.getExplanation()),
            today,
            errors);
    Integer version = chainVersion(request.getExpectedVersion(), errors);
    String preview =
        PayrollPackageCommands.digest(request.getPreviewDigest(), "previewDigest", errors);
    Instant expiresAt = PayrollPackageCommands.instant(request.getExpiresAt(), "expiresAt", errors);
    PayrollPackageCommands.confirmed(request.getReviewedDetails(), "reviewedDetails", errors);
    errors.throwIfAny();
    return new DestinationCommit(draft, version, preview, expiresAt);
  }

  static BatchCommit batchCommit(String idempotencyKey, CommitPaymentBatchRequest request) {
    FieldErrors errors = new FieldErrors();
    PayrollPackageCommands.key(idempotencyKey, errors);
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    unknown(request, errors);
    Integer version =
        PayrollPackageCommands.version(request.getExpectedVersion(), "expectedVersion", 0, errors);
    Integer packageVersion =
        PayrollPackageCommands.version(request.getPackageVersion(), "packageVersion", 1, errors);
    String preview =
        PayrollPackageCommands.digest(request.getPreviewDigest(), "previewDigest", errors);
    Instant expiresAt = PayrollPackageCommands.instant(request.getExpiresAt(), "expiresAt", errors);
    PayrollPackageCommands.confirmed(
        request.getReviewedTotalsAndDestinations(), "reviewedTotalsAndDestinations", errors);
    PayrollPackageCommands.confirmed(
        request.getNoMoneySentAcknowledged(), "noMoneySentAcknowledged", errors);
    errors.throwIfAny();
    return new BatchCommit(version, packageVersion, preview, expiresAt);
  }

  static PaymentBatchCancelReason cancelReason(PaymentBatchCancelCommandRequest request) {
    FieldErrors errors = new FieldErrors();
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    unknown(request, errors);
    PaymentBatchCancelReason reason = reason(request.getReasonCode(), errors);
    errors.throwIfAny();
    return reason;
  }

  static CancelCommit cancelCommit(String idempotencyKey, CommitPaymentBatchCancelRequest request) {
    FieldErrors errors = new FieldErrors();
    PayrollPackageCommands.key(idempotencyKey, errors);
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    unknown(request, errors);
    PaymentBatchCancelReason reason = reason(request.getReasonCode(), errors);
    Integer version =
        PayrollPackageCommands.version(request.getBatchVersion(), "batchVersion", 1, errors);
    String batch = PayrollPackageCommands.digest(request.getBatchDigest(), "batchDigest", errors);
    String preview =
        PayrollPackageCommands.digest(request.getPreviewDigest(), "previewDigest", errors);
    Instant expiresAt = PayrollPackageCommands.instant(request.getExpiresAt(), "expiresAt", errors);
    PayrollPackageCommands.confirmed(
        request.getNoMoneySentAcknowledged(), "noMoneySentAcknowledged", errors);
    errors.throwIfAny();
    return new CancelCommit(reason, version, batch, preview, expiresAt);
  }

  // ------------------------------------------------------------------------------------------
  // Parts
  // ------------------------------------------------------------------------------------------

  private record Raw(
      Object method,
      Object country,
      Object provider,
      Object account,
      Object holder,
      Object branch,
      Object phone,
      Object effectiveFrom,
      Object reason,
      Object explanation) {

    @Override
    public String toString() {
      return "PaymentRaw[redacted]";
    }
  }

  private static void unknown(StrictRequest request, FieldErrors errors) {
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
  }

  private static PaymentDestinationDraft draft(Raw raw, LocalDate today, FieldErrors errors) {
    PaymentMethod method = null;
    if (raw.method() == null) {
      errors.add("method", Constraint.REQUIRED);
    } else if (raw.method() instanceof String text && text.length() <= 20) {
      method = PaymentMethod.parse(text).orElse(null);
    }
    if (raw.method() != null && method == null) {
      errors.add("method", Constraint.FORMAT);
    }
    String country = optionalText(raw.country(), "countryCode", 2, errors);
    String providerCode = optionalText(raw.provider(), "providerCode", 40, errors);
    String account = optionalText(raw.account(), "accountNumber", 80, errors);
    String holder = optionalText(raw.holder(), "holderName", 200, errors);
    String branch = optionalText(raw.branch(), "branchCode", 40, errors);
    String phone = optionalText(raw.phone(), "phoneNumber", 40, errors);
    LocalDate effective = date(raw.effectiveFrom(), today, errors);
    PaymentDestinationReason reason = null;
    if (raw.reason() == null) {
      errors.add("reasonCode", Constraint.REQUIRED);
    } else if (raw.reason() instanceof String text && text.length() <= 40) {
      reason = PaymentDestinationReason.parse(text).orElse(null);
    }
    if (raw.reason() != null && reason == null) {
      errors.add("reasonCode", Constraint.FORMAT);
    }
    String explanation = null;
    boolean shaped = true;
    if (raw.explanation() instanceof String text) {
      explanation = text;
    } else if (raw.explanation() != null) {
      errors.add("explanation", Constraint.FORMAT);
      shaped = false;
    }
    if (reason != null && shaped && !reason.accepts(explanation)) {
      if (explanation == null) {
        errors.add("explanation", Constraint.REQUIRED);
      } else if (!reason.requiresExplanation()) {
        errors.add("explanation", Constraint.FORMAT);
      } else {
        int length = explanation.codePointCount(0, explanation.length());
        boolean ranged =
            length >= PaymentDestinationReason.MIN_EXPLANATION
                && length <= PaymentDestinationReason.MAX_EXPLANATION;
        errors.add("explanation", ranged ? Constraint.FORMAT : Constraint.LENGTH);
      }
    }
    if (method == null || reason == null || !shaped || effective == null) {
      return null;
    }
    PaymentProviders.Provider provider = null;
    String normalizedAccount = null;
    String normalizedPhone = null;
    String normalizedBranch = null;
    String normalizedHolder = null;
    if (method == PaymentMethod.MANUAL) {
      absent(country, "countryCode", errors);
      absent(providerCode, "providerCode", errors);
      absent(account, "accountNumber", errors);
      absent(holder, "holderName", errors);
      absent(branch, "branchCode", errors);
      absent(phone, "phoneNumber", errors);
    } else {
      if (country == null) {
        errors.add("countryCode", Constraint.REQUIRED);
      } else if (!COUNTRY.matcher(country).matches()) {
        errors.add("countryCode", Constraint.FORMAT);
      }
      if (providerCode == null) {
        errors.add("providerCode", Constraint.REQUIRED);
      } else if (!PROVIDER.matcher(providerCode).matches()
          || country == null
          || PaymentProviders.find(providerCode, method, country).isEmpty()) {
        errors.add("providerCode", Constraint.FORMAT);
      } else {
        provider = PaymentProviders.find(providerCode, method, country).get();
      }
      if (holder == null) {
        errors.add("holderName", Constraint.REQUIRED);
      } else if (!holder.equals(holder.strip())
          || holder.codePointCount(0, holder.length()) < 2
          || holder.codePointCount(0, holder.length()) > 100
          || CONTROL.matcher(holder).find()) {
        errors.add("holderName", Constraint.FORMAT);
      } else {
        normalizedHolder = holder;
      }
      if (method == PaymentMethod.BANK_ACCOUNT) {
        absent(phone, "phoneNumber", errors);
        if (account == null) {
          errors.add("accountNumber", Constraint.REQUIRED);
        } else {
          String compact = account.replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
          if (ACCOUNT.matcher(compact).matches()) {
            normalizedAccount = compact;
          } else {
            errors.add("accountNumber", Constraint.FORMAT);
          }
        }
        if (branch != null) {
          if (BRANCH.matcher(branch).matches() && branch.equals(branch.strip())) {
            normalizedBranch = branch;
          } else {
            errors.add("branchCode", Constraint.FORMAT);
          }
        }
      } else {
        absent(account, "accountNumber", errors);
        absent(branch, "branchCode", errors);
        if (phone == null) {
          errors.add("phoneNumber", Constraint.REQUIRED);
        } else if (PHONE.matcher(phone).matches()) {
          normalizedPhone = phone;
        } else {
          errors.add("phoneNumber", Constraint.FORMAT);
        }
      }
    }
    // Any field error above makes the caller throw before this draft is used.
    return new PaymentDestinationDraft(
        method,
        country,
        provider,
        normalizedAccount,
        normalizedHolder,
        normalizedBranch,
        normalizedPhone,
        effective,
        reason,
        explanation);
  }

  private static void absent(String value, String field, FieldErrors errors) {
    if (value != null) {
      errors.add(field, Constraint.FORMAT);
    }
  }

  private static String optionalText(Object raw, String field, int max, FieldErrors errors) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof String text && text.length() <= max) {
      return text;
    }
    errors.add(field, raw instanceof String ? Constraint.LENGTH : Constraint.FORMAT);
    return null;
  }

  private static LocalDate date(Object raw, LocalDate today, FieldErrors errors) {
    if (raw == null) {
      errors.add("effectiveFrom", Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof String text && text.length() == 10 && DATE.matcher(text).matches()) {
      try {
        LocalDate date = LocalDate.parse(text);
        if (date.isBefore(EARLIEST) || date.isAfter(today.plusDays(HORIZON_DAYS))) {
          errors.add("effectiveFrom", Constraint.RANGE);
          return null;
        }
        return date;
      } catch (DateTimeParseException invalid) {
        errors.add("effectiveFrom", Constraint.FORMAT);
        return null;
      }
    }
    errors.add("effectiveFrom", Constraint.FORMAT);
    return null;
  }

  private static Integer chainVersion(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("expectedVersion", Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof Integer || raw instanceof Long) {
      long value = ((Number) raw).longValue();
      if (value >= 0 && value <= MAX_DESTINATION_VERSION) {
        return (int) value;
      }
      errors.add("expectedVersion", Constraint.RANGE);
      return null;
    }
    errors.add("expectedVersion", Constraint.FORMAT);
    return null;
  }

  private static PaymentBatchCancelReason reason(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("reasonCode", Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof String text && text.length() <= 40) {
      PaymentBatchCancelReason parsed = PaymentBatchCancelReason.parse(text).orElse(null);
      if (parsed != null) {
        return parsed;
      }
    }
    errors.add("reasonCode", Constraint.FORMAT);
    return null;
  }
}

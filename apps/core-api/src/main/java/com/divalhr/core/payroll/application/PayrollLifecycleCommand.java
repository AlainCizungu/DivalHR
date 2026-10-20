package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CreatePayrollInputExportRequest;
import com.divalhr.core.payroll.api.LockPayrollInputRunRequest;
import com.divalhr.core.payroll.api.SupersedePayrollInputRunRequest;
import com.divalhr.core.payroll.domain.PayrollLifecycle;
import com.divalhr.core.payroll.domain.PayrollLifecycle.Reason;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validation of the payroll-input lifecycle requests (MVP-092): shapes first (one field error per
 * problem, every unknown property named). The run is a path value, validated like every identifier:
 * malformed is the same safe 404 as unknown.
 */
public final class PayrollLifecycleCommand {

  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  private PayrollLifecycleCommand() {}

  /**
   * A validated supersession command.
   *
   * @param runId the run being replaced
   * @param reason why
   * @param explanation the explanation of OTHER, or {@code null}
   * @param previewDigest the reviewed preview's digest
   */
  public record Supersede(UUID runId, Reason reason, String explanation, String previewDigest) {

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("runId", runId.toString());
      canonical.put("reasonCode", reason.name());
      canonical.put("explanation", explanation == null ? "" : explanation);
      canonical.put("previewDigest", previewDigest);
      canonical.put("confirm", true);
      return canonical;
    }

    /** No explanation, no digest. */
    @Override
    public String toString() {
      return "PayrollLifecycleCommand.Supersede[" + runId + "]";
    }
  }

  /**
   * A validated lock command.
   *
   * @param runId the run being locked
   * @param previewDigest the reviewed preview's digest
   */
  public record Lock(UUID runId, String previewDigest) {

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("runId", runId.toString());
      canonical.put("previewDigest", previewDigest);
      canonical.put("confirm", true);
      return canonical;
    }

    /** No digest. */
    @Override
    public String toString() {
      return "PayrollLifecycleCommand.Lock[" + runId + "]";
    }
  }

  /**
   * A validated export command.
   *
   * @param runId the locked run
   */
  public record Export(UUID runId) {

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("runId", runId.toString());
      canonical.put("confirm", true);
      return canonical;
    }
  }

  static Supersede supersede(String key, String runId, SupersedePayrollInputRunRequest request) {
    FieldErrors errors = new FieldErrors();
    key(key, errors);
    body(request == null, errors);
    UUID run = PayrollInputCommand.id(runId);
    Reason reason = null;
    String explanation = null;
    String digest = null;
    if (request != null) {
      unknown(request.unknownProperties().isEmpty(), errors);
      if (request.getReasonCode() == null) {
        errors.add("reasonCode", Constraint.REQUIRED);
      } else if (request.getReasonCode() instanceof String text) {
        try {
          reason = Reason.valueOf(text);
        } catch (IllegalArgumentException unknown) {
          errors.add("reasonCode", Constraint.FORMAT);
        }
      } else {
        errors.add("reasonCode", Constraint.FORMAT);
      }
      Object raw = request.getExplanation();
      if (reason == Reason.OTHER) {
        if (raw == null) {
          errors.add("explanation", Constraint.REQUIRED);
        } else if (!(raw instanceof String text) || !PayrollLifecycle.plainExplanation(text)) {
          errors.add("explanation", Constraint.FORMAT);
        } else {
          explanation = text;
        }
      } else if (raw != null) {
        errors.add("explanation", Constraint.FORMAT);
      }
      digest = digest(request.getPreviewDigest(), errors);
      confirm(request.getConfirm(), errors);
    }
    errors.throwIfAny();
    return new Supersede(run, reason, explanation, digest);
  }

  static Lock lock(String key, String runId, LockPayrollInputRunRequest request) {
    FieldErrors errors = new FieldErrors();
    key(key, errors);
    body(request == null, errors);
    UUID run = PayrollInputCommand.id(runId);
    String digest = null;
    if (request != null) {
      unknown(request.unknownProperties().isEmpty(), errors);
      digest = digest(request.getPreviewDigest(), errors);
      confirm(request.getConfirm(), errors);
    }
    errors.throwIfAny();
    return new Lock(run, digest);
  }

  static Export export(String key, CreatePayrollInputExportRequest request) {
    FieldErrors errors = new FieldErrors();
    key(key, errors);
    body(request == null, errors);
    UUID run = null;
    boolean malformed = false;
    if (request != null) {
      unknown(request.unknownProperties().isEmpty(), errors);
      if (request.getRunId() == null) {
        errors.add("runId", Constraint.REQUIRED);
      } else if (request.getRunId() instanceof String text && UUID_SHAPE.matcher(text).matches()) {
        run = UUID.fromString(text.toLowerCase(Locale.ROOT));
      } else {
        malformed = true;
      }
      confirm(request.getConfirm(), errors);
    }
    errors.throwIfAny();
    if (malformed || run == null) {
      // After every shape check: a malformed run is the same 404 as an unknown one.
      throw PayrollInputCommand.notFound();
    }
    return new Export(run);
  }

  private static void body(boolean missing, FieldErrors errors) {
    if (missing) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
    }
  }

  private static void unknown(boolean none, FieldErrors errors) {
    if (!none) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
  }

  private static void confirm(Object confirm, FieldErrors errors) {
    if (!Boolean.TRUE.equals(confirm)) {
      errors.add("confirm", Constraint.REQUIRED);
    }
  }

  private static String digest(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("previewDigest", Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !DIGEST.matcher(text).matches()) {
      errors.add("previewDigest", Constraint.FORMAT);
      return null;
    }
    return text;
  }

  private static void key(String idempotencyKey, FieldErrors errors) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
  }
}

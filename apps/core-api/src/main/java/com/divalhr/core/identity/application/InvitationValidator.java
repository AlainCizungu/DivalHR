package com.divalhr.core.identity.application;

import com.divalhr.core.identity.api.CreateInvitationRequest;
import com.divalhr.core.identity.api.CreateTenantAdminBootstrapRequest;
import com.divalhr.core.identity.api.StrictRequest;
import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.identity.domain.InvitationLocale;
import com.divalhr.core.identity.domain.InvitationStatus;
import com.divalhr.core.identity.domain.TenantRole;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.pagination.PageRequest;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates invitation requests into stable codes. Submitted values, and email addresses in
 * particular, never appear in error parameters. An unsupported role (including {@code
 * platform-admin}) is {@code FORMAT} exactly like any unknown value.
 */
@Component
public class InvitationValidator {

  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  /**
   * A normalized create command.
   *
   * @param email normalized address
   * @param role role
   * @param locale language
   */
  public record CreateCommand(EmailAddress email, TenantRole role, InvitationLocale locale) {}

  /**
   * Validates a create request.
   *
   * @param idempotencyKey header value
   * @param request body
   * @return normalized command
   */
  public CreateCommand create(String idempotencyKey, CreateInvitationRequest request) {
    return create(idempotencyKey, request, request);
  }

  private CreateCommand create(
      String idempotencyKey, CreateInvitationRequest request, StrictRequest submitted) {
    FieldErrors errors = new FieldErrors();
    key(errors, idempotencyKey);
    unknown(errors, submitted);
    Optional<EmailAddress.Defect> defect = EmailAddress.defectOf(request.getEmail());
    defect.ifPresent(
        d ->
            errors.add(
                "email",
                switch (d) {
                  case REQUIRED -> Constraint.REQUIRED;
                  case LENGTH -> Constraint.LENGTH;
                  case FORMAT -> Constraint.FORMAT;
                }));
    TenantRole role = null;
    if (request.getRole() == null || request.getRole().isEmpty()) {
      errors.add("role", Constraint.REQUIRED);
    } else {
      role = TenantRole.fromWire(request.getRole()).orElse(null);
      if (role == null) {
        errors.add("role", Constraint.FORMAT);
      }
    }
    InvitationLocale locale = null;
    if (request.getLocale() == null || request.getLocale().isEmpty()) {
      errors.add("locale", Constraint.REQUIRED);
    } else {
      locale = InvitationLocale.fromTag(request.getLocale()).orElse(null);
      if (locale == null) {
        errors.add("locale", Constraint.FORMAT);
      }
    }
    errors.throwIfAny();
    return new CreateCommand(EmailAddress.parse(request.getEmail()).orElseThrow(), role, locale);
  }

  /**
   * Validates a bootstrap request (MVP-014): the address and locale exactly like {@link #create};
   * the role is fixed to {@code tenant-admin} by the operation.
   *
   * @param idempotencyKey header value
   * @param request body
   * @return normalized command with role {@code tenant-admin}
   */
  public CreateCommand bootstrap(String idempotencyKey, CreateTenantAdminBootstrapRequest request) {
    return create(
        idempotencyKey,
        CreateInvitationRequest.of(
            request.getEmail(), TenantRole.TENANT_ADMIN.wireName(), request.getLocale()),
        request);
  }

  /**
   * Validates a required idempotency key alone (operations without another input).
   *
   * @param idempotencyKey header value
   * @return the key
   */
  public String idempotencyKey(String idempotencyKey) {
    FieldErrors errors = new FieldErrors();
    key(errors, idempotencyKey);
    errors.throwIfAny();
    return idempotencyKey;
  }

  /**
   * Parses a platform operation's organization ID. Malformed IDs are reported exactly like unknown
   * ones (404 {@code ORGANIZATION_NOT_FOUND}) and are never echoed or logged.
   *
   * @param raw path value
   * @return the tenant
   */
  public TenantId organizationId(String raw) {
    if (raw == null || !UUID_SHAPE.matcher(raw).matches()) {
      throw new ApiException(ErrorCode.ORGANIZATION_NOT_FOUND, Map.of());
    }
    return new TenantId(UUID.fromString(raw));
  }

  /**
   * Validates an invitation id from the path, and optionally an idempotency key.
   *
   * @param idempotencyKey header value, or {@code null} when the operation takes none
   * @param requireKey whether the key is required
   * @param raw path value
   * @return id
   */
  public UUID invitationId(String idempotencyKey, boolean requireKey, String raw) {
    FieldErrors errors = new FieldErrors();
    if (requireKey) {
      key(errors, idempotencyKey);
    }
    if (raw == null || !UUID_SHAPE.matcher(raw).matches()) {
      errors.add("invitationId", Constraint.FORMAT);
    }
    errors.throwIfAny();
    return UUID.fromString(raw);
  }

  /**
   * A validated list request.
   *
   * @param status optional status filter
   * @param limit page size
   */
  public record ListQuery(InvitationStatus status, int limit) {}

  /**
   * Validates list parameters.
   *
   * @param status raw status filter or {@code null}
   * @param limit raw limit or {@code null}
   * @return the query
   */
  public ListQuery list(String status, String limit) {
    FieldErrors errors = new FieldErrors();
    InvitationStatus filter = null;
    if (status != null) {
      try {
        filter = InvitationStatus.valueOf(status);
      } catch (IllegalArgumentException unknownStatus) {
        errors.add("status", Constraint.FORMAT);
      }
    }
    PageRequest page = PageRequest.parse(limit);
    if (page == null) {
      errors.add("limit", Constraint.RANGE);
    }
    errors.throwIfAny();
    return new ListQuery(filter, page.limit());
  }

  /**
   * Validates the anonymous request body shape. The token itself is checked by the caller so that a
   * malformed token is indistinguishable from an unknown one.
   *
   * @param request body
   */
  public void tokenBody(StrictRequest request) {
    FieldErrors errors = new FieldErrors();
    unknown(errors, request);
    errors.throwIfAny();
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
}

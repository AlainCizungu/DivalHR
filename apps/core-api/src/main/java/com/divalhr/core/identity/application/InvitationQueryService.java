package com.divalhr.core.identity.application;

import com.divalhr.core.identity.api.InvitationPageResponse;
import com.divalhr.core.identity.api.InvitationResponse;
import com.divalhr.core.identity.domain.Invitation;
import com.divalhr.core.identity.internal.JdbcInvitationRepository;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Newest-first, keyset-paginated invitation listing for tenant administrators. Cursors are bound to
 * {@code invitation.list}, the verified tenant and the status filter. The shared cursor codec is
 * unchanged: the position's code slot carries the creation time as 16 zero-padded digits of epoch
 * microseconds, validated again on decode.
 */
@Service
public class InvitationQueryService {

  /** List operation (metrics, cursor binding). */
  public static final String LIST_INVITATIONS = "invitation.list";

  private static final Pattern MICROS = Pattern.compile("^[0-9]{16}$");

  private final InvitationValidator validator;
  private final CursorCodec cursors;
  private final JdbcInvitationRepository invitations;
  private final OperationMetrics metrics;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param validator parameter validator
   * @param cursors cursor codec
   * @param invitations invitation repository
   * @param metrics operation metrics
   */
  public InvitationQueryService(
      InvitationValidator validator,
      CursorCodec cursors,
      JdbcInvitationRepository invitations,
      OperationMetrics metrics) {
    this.validator = validator;
    this.cursors = cursors;
    this.invitations = invitations;
    this.metrics = metrics;
    this.clock = Clock.systemUTC();
  }

  /**
   * Lists the tenant's invitations.
   *
   * @param tenant verified tenant
   * @param status raw status filter or {@code null}
   * @param cursor opaque cursor or {@code null}
   * @param limit raw limit or {@code null}
   * @return one page
   */
  @Transactional(readOnly = true)
  public InvitationPageResponse list(TenantId tenant, String status, String cursor, String limit) {
    try {
      InvitationValidator.ListQuery query = validator.list(status, limit);
      CursorScope scope =
          new CursorScope(
              LIST_INVITATIONS,
              tenant,
              Map.of("status", query.status() == null ? "*" : query.status().name()));
      Instant afterCreatedAt = null;
      java.util.UUID afterId = null;
      if (cursor != null) {
        KeysetPosition after = cursors.decode(cursor, scope);
        if (!MICROS.matcher(after.code()).matches()) {
          throw new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
        }
        afterCreatedAt = fromMicros(Long.parseLong(after.code()));
        afterId = after.id();
      }
      Instant now = Instant.now(clock);
      List<Invitation> rows =
          invitations.page(tenant, query.status(), afterCreatedAt, afterId, query.limit() + 1, now);
      List<Invitation> shown = rows.subList(0, Math.min(rows.size(), query.limit()));
      String next = null;
      if (rows.size() > query.limit()) {
        Invitation last = shown.get(shown.size() - 1);
        next =
            cursors.encode(
                scope,
                new KeysetPosition(String.format("%016d", toMicros(last.createdAt())), last.id()));
      }
      InvitationPageResponse page =
          new InvitationPageResponse(
              shown.stream().map(row -> InvitationResponse.from(row, now)).toList(), next);
      metrics.record(LIST_INVITATIONS, Outcome.LISTED);
      return page;
    } catch (ApiException rejected) {
      metrics.record(
          LIST_INVITATIONS,
          rejected.code() == ErrorCode.INTERNAL_ERROR
              ? Outcome.FAILURE
              : Outcome.VALIDATION_FAILED);
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(LIST_INVITATIONS, Outcome.FAILURE);
      throw failure;
    }
  }

  static long toMicros(Instant instant) {
    Instant micros = instant.truncatedTo(ChronoUnit.MICROS);
    return Math.addExact(
        Math.multiplyExact(micros.getEpochSecond(), 1_000_000L), micros.getNano() / 1_000L);
  }

  static Instant fromMicros(long micros) {
    return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L))
        .plusNanos(Math.floorMod(micros, 1_000_000L) * 1_000L);
  }
}

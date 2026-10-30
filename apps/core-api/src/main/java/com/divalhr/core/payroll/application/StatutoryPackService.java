package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.StatutoryResponses.PackDetail;
import com.divalhr.core.payroll.api.StatutoryResponses.PackPage;
import com.divalhr.core.payroll.api.StatutoryResponses.PackSummary;
import com.divalhr.core.payroll.api.StatutoryResponses.Verification;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository.PackPosition;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository.PackRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository.PackWithRules;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository.SourceRow;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads of the platform's statutory rule packs for tenant administrators with privileged MFA
 * (MVP-094B, Issue #179, ADR 0034). Rule packs are immutable, platform-owned reference data shared
 * by every tenant: nothing here creates, edits or verifies a pack, and no tenant data is read. The
 * verification evidence (reviewer identity) is restricted operational evidence: it is returned to
 * the administrator but never audited, logged or counted in a metric. Every read is a fail-closed
 * disclosure whose audit metadata is counts only.
 */
@Service
public class StatutoryPackService {

  /** Reads (interceptor operation and disclosure audit action). */
  public static final String READ = StatutoryRecorder.PACK_READ;

  /** Per-subject reads of every statutory read operation. */
  public static final String SUBJECT_READ_BUCKET = "statutory-read";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.statutory-pack");
  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final String ROLE = "tenant-admin";
  private static final String CURSOR_CODE = "PACK";

  private final JdbcStatutoryRulePackRepository packs;
  private final CompensationCalendar calendar;
  private final StatutoryRecorder recorder;
  private final PayrollOperations plumbing;
  private final CursorCodec cursors;

  /**
   * Creates the service.
   *
   * @param packs platform-owned rule packs
   * @param calendar server clock
   * @param recorder audit
   * @param plumbing transactions, metrics and logs
   * @param cursors signed cursors
   */
  public StatutoryPackService(
      JdbcStatutoryRulePackRepository packs,
      CompensationCalendar calendar,
      StatutoryRecorder recorder,
      PayrollOperations plumbing,
      CursorCodec cursors) {
    this.packs = packs;
    this.calendar = calendar;
    this.recorder = recorder;
    this.plumbing = plumbing;
    this.cursors = cursors;
  }

  /**
   * A page of rule packs.
   *
   * @param caller verified tenant administrator
   * @param status raw status filter, or {@code null}
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public PackPage list(PayrollCaller caller, String status, String cursor, String limit) {
    return plumbing.observed(
        LOG,
        "statutory_rule_pack_read",
        READ,
        () -> {
          FieldErrors errors = new FieldErrors();
          String filter = StatutoryCommands.statusFilter(status, errors);
          int size =
              PayrollCommands.limit(
                  limit,
                  StatutoryCommands.DEFAULT_LIST_LIMIT,
                  StatutoryCommands.MAX_LIST_LIMIT,
                  errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                CursorScope scope = scope(caller, filter, size);
                PackPosition after = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  PackRow at =
                      packs
                          .pack(position.id())
                          .filter(row -> CURSOR_CODE.equals(position.code()))
                          .filter(row -> filter == null || filter.equals(row.status()))
                          .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                  after = new PackPosition(at.effectiveFrom(), at.code(), at.version());
                }
                List<PackRow> rows = packs.packPage(filter, after, size + 1);
                List<PackRow> shown = rows.subList(0, Math.min(rows.size(), size));
                String next = null;
                if (rows.size() > size) {
                  next =
                      cursors.encode(
                          scope,
                          new KeysetPosition(CURSOR_CODE, shown.get(shown.size() - 1).id()));
                }
                recorder.disclosed(
                    caller,
                    READ,
                    "statutory-rule-pack",
                    caller.tenant().value(),
                    Map.of(),
                    "list",
                    cursor == null,
                    shown.size(),
                    calendar.now());
                List<PackSummary> items = shown.stream().map(StatutoryViews::summary).toList();
                return new PackPage(items, next);
              });
        });
  }

  /**
   * One rule pack with its sources, rules and verification evidence.
   *
   * @param caller verified tenant administrator
   * @param packId raw path value
   * @return the pack, after its disclosure audit committed
   */
  public PackDetail get(PayrollCaller caller, String packId) {
    return plumbing.observed(
        LOG,
        "statutory_rule_pack_read",
        READ,
        () -> {
          UUID id = StatutoryCommands.packId(packId);
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                PackWithRules found =
                    packs
                        .packWithRules(id)
                        .orElseThrow(
                            () ->
                                new ApiException(
                                    ErrorCode.STATUTORY_RULE_PACK_NOT_FOUND, Map.of()));
                PackRow pack = found.row();
                List<SourceRow> sources = packs.sources(id);
                recorder.disclosed(
                    caller,
                    READ,
                    "statutory-rule-pack",
                    id,
                    Map.of(
                        "packId", id.toString(), "packVersion", Integer.toString(pack.version())),
                    "pack",
                    true,
                    1,
                    calendar.now());
                Verification verification =
                    pack.verifiedAt() == null
                        ? null
                        : new Verification(
                            pack.reviewerName(),
                            pack.reviewerOrganization(),
                            pack.reviewedOn(),
                            pack.attested(),
                            pack.verifiedAt());
                return new PackDetail(
                    StatutoryViews.summary(pack),
                    sources.stream().map(StatutoryViews::source).toList(),
                    StatutoryViews.components(StatutoryEvaluator.rules(found.rules(), sources)),
                    verification);
              });
        });
  }

  private static CursorScope scope(PayrollCaller caller, String status, int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", "list");
    filters.put("status", status == null ? "ALL" : status);
    filters.put("role", ROLE);
    filters.put("subject", caller.subject());
    filters.put("limit", Integer.toString(size));
    return new CursorScope(READ, caller.tenant(), filters);
  }
}

package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.PayrollPackageResponses.PackageDetail;
import com.divalhr.core.payroll.api.PayrollPackageResponses.PackagePage;
import com.divalhr.core.payroll.api.PayrollPackageResponses.VoidEvidence;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository.PackageRow;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository.PackagePosition;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository.VoidRow;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads of a legal entity's payroll packages for tenant administrators with privileged MFA
 * (MVP-094C, Issue #181, ADR 0035): the list and the detail with the evidence and, for a voided
 * package, the void evidence. Authorization has run before this; tenant, actor and scope never come
 * from the request. The approver subject is never read. Every read is a fail-closed disclosure (the
 * read fails if its audit record cannot be written). Restricted financial and HR data: amounts,
 * digests and the void explanation never enter logs, metrics, audit metadata or cursors.
 */
@Service
public class PayrollPackageService {

  /** List and detail reads (interceptor operation, cursor binding and audit action). */
  public static final String READ = PayrollPackageRecorder.PACKAGE_READ;

  /** Per-subject reads (shared by every package and payslip read). */
  public static final String SUBJECT_READ_BUCKET = "payroll-package-read";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.payroll-package");
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
  private static final String ROLE = "tenant-admin";
  private static final String PACKAGE_CODE = "PKG";

  private final JdbcPayrollPackageRepository packages;
  private final PayrollEntities entities;
  private final CompensationCalendar calendar;
  private final PayrollPackageRecorder recorder;
  private final PayrollOperations plumbing;
  private final CursorCodec cursors;

  /**
   * Creates the service.
   *
   * @param packages payroll package storage
   * @param entities legal entity resolver
   * @param calendar business date
   * @param recorder audit
   * @param plumbing transactions, metrics and logs
   * @param cursors signed cursors
   */
  public PayrollPackageService(
      JdbcPayrollPackageRepository packages,
      PayrollEntities entities,
      CompensationCalendar calendar,
      PayrollPackageRecorder recorder,
      PayrollOperations plumbing,
      CursorCodec cursors) {
    this.packages = packages;
    this.entities = entities;
    this.calendar = calendar;
    this.recorder = recorder;
    this.plumbing = plumbing;
    this.cursors = cursors;
  }

  /**
   * A page of the legal entity's packages: month descending, version descending.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param status raw status filter, or {@code null}
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public PackagePage list(
      PayrollCaller caller, String legalEntityId, String status, String cursor, String limit) {
    return plumbing.observed(
        LOG,
        "payroll_package_read",
        READ,
        () -> {
          PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
          FieldErrors errors = new FieldErrors();
          int size =
              PayrollCommands.limit(
                  limit,
                  PayrollPackageCommands.DEFAULT_PACKAGE_LIMIT,
                  PayrollPackageCommands.MAX_PACKAGE_LIMIT,
                  errors);
          String filter = PayrollPackageCommands.statusFilter(status, errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                CursorScope scope = scope(caller, entity, filter, size);
                PackagePosition after = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  PackageRow at =
                      packages
                          .pack(tenant, entity, position.id())
                          .filter(row -> PACKAGE_CODE.equals(position.code()))
                          .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                  after = new PackagePosition(at.periodStart(), at.version(), at.id());
                }
                List<PackageRow> rows =
                    packages.packagePage(tenant, entity, filter, after, size + 1);
                List<PackageRow> shown = rows.subList(0, Math.min(rows.size(), size));
                String next = null;
                if (rows.size() > size) {
                  next =
                      cursors.encode(
                          scope,
                          new KeysetPosition(PACKAGE_CODE, shown.get(shown.size() - 1).id()));
                }
                recorder.disclosed(
                    caller,
                    READ,
                    "payroll-package",
                    entity,
                    Map.of("legalEntityId", entity.toString()),
                    "list",
                    cursor == null,
                    shown.size(),
                    calendar.now());
                return new PackagePage(
                    shown.stream().map(PayrollPackageViews::summary).toList(), next);
              });
        });
  }

  /**
   * One package with its evidence and, when voided, the void evidence.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param packageId raw path value
   * @return the package, after its disclosure audit committed
   */
  public PackageDetail get(PayrollCaller caller, String legalEntityId, String packageId) {
    return plumbing.observed(
        LOG,
        "payroll_package_read",
        READ,
        () -> {
          ErrorCode notFound = ErrorCode.PAYROLL_PACKAGE_NOT_FOUND;
          UUID entityId = PayrollCommands.id(legalEntityId, notFound);
          UUID id = PayrollCommands.id(packageId, notFound);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity = entities.require(tenant, entityId, notFound);
                PackageRow row =
                    packages
                        .pack(tenant, entity, id)
                        .orElseThrow(() -> new ApiException(notFound, Map.of()));
                VoidEvidence evidence = null;
                if ("VOIDED".equals(row.status())) {
                  VoidRow voided =
                      packages
                          .voidEvidence(tenant, entity, id)
                          .orElseThrow(() -> new IllegalStateException("void evidence missing"));
                  evidence =
                      new VoidEvidence(
                          voided.reasonCode(), voided.explanation(), voided.voidedAt());
                }
                recorder.disclosed(
                    caller,
                    READ,
                    "payroll-package",
                    id,
                    Map.of("legalEntityId", entity.toString(), "packageId", id.toString()),
                    "package",
                    true,
                    1,
                    calendar.now());
                return new PackageDetail(
                    PayrollPackageViews.summary(row), PayrollPackageViews.evidence(row), evidence);
              });
        });
  }

  private static CursorScope scope(PayrollCaller caller, UUID entity, String status, int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", "list");
    filters.put("entity", entity.toString());
    filters.put("status", status == null ? "" : status);
    filters.put("role", ROLE);
    filters.put("subject", caller.subject());
    filters.put("limit", Integer.toString(size));
    return new CursorScope(READ, caller.tenant(), filters);
  }
}

package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.PayrollPackageResponses.SlipPage;
import com.divalhr.core.payroll.api.PayrollPackageResponses.SlipView;
import com.divalhr.core.payroll.domain.PayrollVoidReason;
import com.divalhr.core.payroll.domain.PayslipLocale;
import com.divalhr.core.payroll.domain.PayslipSnapshot;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository.EmployeePosition;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository.SlipRow;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmployeeAccessLinks.SelfLink;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.security.ScopeAuthorizationInterceptor;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Published payslips (MVP-094C, Issue #181, ADR 0035 sections 12 and 13): the accessible
 * HTML-primary view and the deterministic PDF, for tenant administrators with privileged MFA (any
 * payslip of a package of their tenant) and for employees (only their own, bound on every request
 * to the caller's current active employee-access link). Authorization has run before this; tenant,
 * actor and employee never come from the request. A payslip is visible only after its package is
 * APPROVED and stays visible, visibly VOIDED, after a void. The locale selects wording only, never
 * an amount.
 *
 * <p>Every read and download is a fail-closed disclosure: the read fails if its audit record cannot
 * be written, and the download audit commits before any byte is returned. The PDF is rendered on
 * demand from the immutable canonical snapshot inside the read transaction, so a render failure
 * records nothing and returns nothing. Restricted financial and HR data: amounts, employee numbers,
 * snapshots, digests and filenames never enter logs, metrics, audit metadata or cursors.
 */
@Service
public class PayslipService {

  /** Administrator list and detail reads (interceptor operation and audit action). */
  public static final String READ = PayrollPackageRecorder.PAYSLIP_READ;

  /** Administrator PDF downloads (interceptor operation and audit action). */
  public static final String DOWNLOAD = PayrollPackageRecorder.PAYSLIP_DOWNLOAD;

  /** Employee reads of their own payslips (interceptor operation and cursor binding). */
  public static final String SELF_READ = "payslip.self-read";

  /** Employee PDF downloads of their own payslips (interceptor operation). */
  public static final String SELF_DOWNLOAD = "payslip.self-download";

  /** Per-subject downloads. */
  public static final String SUBJECT_DOWNLOAD_BUCKET = "payslip-download";

  /** Per-tenant downloads. */
  public static final String TENANT_DOWNLOAD_BUCKET = "payslip-download";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.payslip");
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
  private static final String ADMIN_ROLE = "tenant-admin";
  private static final String EMPLOYEE_ROLE = "employee";
  private static final String SLIP_CODE = "SLIP";

  /**
   * A rendered payslip with its generated filename.
   *
   * @param pdf the document
   * @param filename {@code payslip-<YYYY-MM>-<8 hex of the payslip id>.pdf}
   */
  public record Download(PayslipPdf pdf, String filename) {

    @Override
    public String toString() {
      return "PayslipDownload";
    }
  }

  private final JdbcPayrollPackageRepository packages;
  private final EmployeeAccessLinks links;
  private final PayrollEntities entities;
  private final PayslipPdfRenderer renderer;
  private final CompensationCalendar calendar;
  private final PayrollPackageRecorder recorder;
  private final PayrollOperations plumbing;
  private final CursorCodec cursors;
  private final MeterRegistry meters;

  /**
   * Creates the service.
   *
   * @param packages payroll package storage
   * @param links the identity port (joins this module's transactions)
   * @param entities legal entity resolver
   * @param renderer the PDF renderer
   * @param calendar business date
   * @param recorder audit
   * @param plumbing transactions, metrics and logs
   * @param cursors signed cursors
   * @param meters bounded render metrics and the self-service denial counter
   */
  public PayslipService(
      JdbcPayrollPackageRepository packages,
      EmployeeAccessLinks links,
      PayrollEntities entities,
      PayslipPdfRenderer renderer,
      CompensationCalendar calendar,
      PayrollPackageRecorder recorder,
      PayrollOperations plumbing,
      CursorCodec cursors,
      MeterRegistry meters) {
    this.packages = packages;
    this.links = links;
    this.entities = entities;
    this.renderer = renderer;
    this.calendar = calendar;
    this.recorder = recorder;
    this.plumbing = plumbing;
    this.cursors = cursors;
    this.meters = meters;
  }

  // ------------------------------------------------------------------------------------------
  // Administrator
  // ------------------------------------------------------------------------------------------

  /**
   * A page of a package's payslips by employee number in byte order.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param packageId raw path value
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the page, after its disclosure audit committed (first page)
   */
  public SlipPage listPackage(
      PayrollCaller caller, String legalEntityId, String packageId, String cursor, String limit) {
    return plumbing.observed(
        LOG,
        "payslip_read",
        READ,
        () -> {
          ErrorCode notFound = ErrorCode.PAYROLL_PACKAGE_NOT_FOUND;
          UUID entityId = PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
          UUID id = PayrollCommands.id(packageId, notFound);
          FieldErrors errors = new FieldErrors();
          int size =
              PayrollCommands.limit(
                  limit,
                  PayrollPackageCommands.DEFAULT_SLIP_LIMIT,
                  PayrollPackageCommands.MAX_SLIP_LIMIT,
                  errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, entityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                if (packages.pack(tenant, entity, id).isEmpty()) {
                  throw new ApiException(notFound, Map.of());
                }
                CursorScope scope = scope(caller, READ, ADMIN_ROLE, "package", entity, id, size);
                String afterNumber = null;
                UUID afterId = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  SlipRow at =
                      packages
                          .slip(tenant, entity, id, position.id())
                          .filter(row -> SLIP_CODE.equals(position.code()))
                          .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                  afterNumber = at.employeeNumber();
                  afterId = at.id();
                }
                List<SlipRow> rows =
                    packages.slipPage(tenant, entity, id, afterNumber, afterId, size + 1);
                List<SlipRow> shown = rows.subList(0, Math.min(rows.size(), size));
                String next = null;
                if (rows.size() > size) {
                  next =
                      cursors.encode(
                          scope, new KeysetPosition(SLIP_CODE, shown.get(shown.size() - 1).id()));
                }
                if (cursor == null) {
                  recorder.disclosed(
                      caller,
                      READ,
                      "payroll-package",
                      id,
                      Map.of("legalEntityId", entity.toString(), "packageId", id.toString()),
                      "package-payslips",
                      true,
                      shown.size(),
                      calendar.now());
                }
                return new SlipPage(
                    shown.stream().map(PayrollPackageViews::slipSummary).toList(), next);
              });
        });
  }

  /**
   * One payslip of a package, as the accessible HTML-primary view.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param packageId raw path value
   * @param payslipId raw path value
   * @param locale the raw {@code locale} query value, or {@code null}
   * @param acceptLanguage the {@code Accept-Language} header, or {@code null}
   * @return the view, after its disclosure audit committed
   */
  public SlipView getPackage(
      PayrollCaller caller,
      String legalEntityId,
      String packageId,
      String payslipId,
      String locale,
      String acceptLanguage) {
    return plumbing.observed(
        LOG,
        "payslip_read",
        READ,
        () -> {
          ErrorCode notFound = ErrorCode.PAYSLIP_NOT_FOUND;
          UUID entityId = PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
          UUID pkg = PayrollCommands.id(packageId, notFound);
          UUID id = PayrollCommands.id(payslipId, notFound);
          PayslipLocale wording = PayrollPackageCommands.locale(locale, acceptLanguage);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, entityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                SlipRow row =
                    packages
                        .slip(tenant, entity, pkg, id)
                        .orElseThrow(() -> new ApiException(notFound, Map.of()));
                SlipView view = PayrollPackageViews.slipView(row, parse(row), wording);
                disclose(caller, READ, row, "package-payslip");
                return view;
              });
        });
  }

  /**
   * One payslip of a package as a PDF.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param packageId raw path value
   * @param payslipId raw path value
   * @param locale the raw {@code locale} query value, or {@code null}
   * @param acceptLanguage the {@code Accept-Language} header, or {@code null}
   * @return the document, after its download audit committed
   */
  public Download downloadPackage(
      PayrollCaller caller,
      String legalEntityId,
      String packageId,
      String payslipId,
      String locale,
      String acceptLanguage) {
    return plumbing.observed(
        LOG,
        "payslip_download",
        DOWNLOAD,
        () -> {
          ErrorCode notFound = ErrorCode.PAYSLIP_NOT_FOUND;
          UUID entityId = PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
          UUID pkg = PayrollCommands.id(packageId, notFound);
          UUID id = PayrollCommands.id(payslipId, notFound);
          PayslipLocale wording = PayrollPackageCommands.locale(locale, acceptLanguage);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, entityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                SlipRow row =
                    packages
                        .slip(tenant, entity, pkg, id)
                        .orElseThrow(() -> new ApiException(notFound, Map.of()));
                Download download = render(row, wording);
                disclose(caller, DOWNLOAD, row, "pdf");
                return download;
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Employee
  // ------------------------------------------------------------------------------------------

  /**
   * A page of the caller's own payslips, newest first.
   *
   * @param caller verified employee
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public SlipPage listMine(PayrollCaller caller, String cursor, String limit) {
    return own(
        SELF_READ,
        "payslip_self_read",
        () -> {
          FieldErrors errors = new FieldErrors();
          int size =
              PayrollCommands.limit(
                  limit,
                  PayrollPackageCommands.DEFAULT_PACKAGE_LIMIT,
                  PayrollPackageCommands.MAX_PACKAGE_LIMIT,
                  errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                SelfLink link = link(caller);
                CursorScope scope =
                    scope(caller, SELF_READ, EMPLOYEE_ROLE, "own", null, null, size);
                EmployeePosition after = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  SlipRow at =
                      packages
                          .mySlip(tenant, link.employeeId(), position.id())
                          .filter(row -> SLIP_CODE.equals(position.code()))
                          .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                  after = new EmployeePosition(at.approvedAt(), at.id());
                }
                List<SlipRow> rows =
                    packages.mySlipPage(tenant, link.employeeId(), after, size + 1);
                List<SlipRow> shown = rows.subList(0, Math.min(rows.size(), size));
                String next = null;
                if (rows.size() > size) {
                  next =
                      cursors.encode(
                          scope, new KeysetPosition(SLIP_CODE, shown.get(shown.size() - 1).id()));
                }
                recorder.disclosed(
                    caller,
                    READ,
                    "payslip-list",
                    link.employeeId(),
                    Map.of(),
                    "own-list",
                    cursor == null,
                    shown.size(),
                    calendar.now());
                return new SlipPage(
                    shown.stream().map(PayrollPackageViews::slipSummary).toList(), next);
              });
        });
  }

  /**
   * One of the caller's own payslips, as the accessible HTML-primary view.
   *
   * @param caller verified employee
   * @param payslipId raw path value
   * @param locale the raw {@code locale} query value, or {@code null}
   * @param acceptLanguage the {@code Accept-Language} header, or {@code null}
   * @return the view, after its disclosure audit committed
   */
  public SlipView getMine(
      PayrollCaller caller, String payslipId, String locale, String acceptLanguage) {
    return own(
        SELF_READ,
        "payslip_self_read",
        () -> {
          UUID id = PayrollPackageCommands.payslipId(payslipId);
          PayslipLocale wording = PayrollPackageCommands.locale(locale, acceptLanguage);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                SlipRow row = mine(caller, id);
                SlipView view = PayrollPackageViews.slipView(row, parse(row), wording);
                disclose(caller, READ, row, "own-payslip");
                return view;
              });
        });
  }

  /**
   * One of the caller's own payslips as a PDF.
   *
   * @param caller verified employee
   * @param payslipId raw path value
   * @param locale the raw {@code locale} query value, or {@code null}
   * @param acceptLanguage the {@code Accept-Language} header, or {@code null}
   * @return the document, after its download audit committed
   */
  public Download downloadMine(
      PayrollCaller caller, String payslipId, String locale, String acceptLanguage) {
    return own(
        SELF_DOWNLOAD,
        "payslip_self_download",
        () -> {
          UUID id = PayrollPackageCommands.payslipId(payslipId);
          PayslipLocale wording = PayrollPackageCommands.locale(locale, acceptLanguage);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                SlipRow row = mine(caller, id);
                Download download = render(row, wording);
                disclose(caller, DOWNLOAD, row, "own-pdf");
                return download;
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Parts
  // ------------------------------------------------------------------------------------------

  /** Runs an employee operation; a missing link is counted as a self-service denial. */
  private <T> T own(String operation, String event, Supplier<T> work) {
    try {
      return plumbing.observed(LOG, event, operation, work);
    } catch (ApiException denied) {
      if (denied.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "link_required");
      }
      throw denied;
    }
  }

  private SelfLink link(PayrollCaller caller) {
    return links
        .linkedEmployee(caller.tenant(), caller.subject())
        .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
  }

  /** The employee's own payslip: the employee predicate is part of the statement. */
  private SlipRow mine(PayrollCaller caller, UUID payslipId) {
    SelfLink link = link(caller);
    return packages
        .mySlip(caller.tenant(), link.employeeId(), payslipId)
        .orElseThrow(() -> new ApiException(ErrorCode.PAYSLIP_NOT_FOUND, Map.of()));
  }

  private void disclose(PayrollCaller caller, String action, SlipRow row, String view) {
    recorder.disclosed(
        caller,
        action,
        "payslip",
        row.id(),
        Map.of(
            "legalEntityId",
            row.legalEntityId().toString(),
            "packageId",
            row.packageId().toString(),
            "payslipId",
            row.id().toString()),
        view,
        true,
        1,
        calendar.now());
  }

  private static PayslipSnapshot parse(SlipRow row) {
    try {
      return PayslipSnapshot.parse(row.snapshotText());
    } catch (RuntimeException unreadable) {
      throw new IllegalStateException("payslip snapshot is unreadable");
    }
  }

  /** Renders the PDF; a failure is the safe 500 with nothing partial and nothing recorded. */
  private Download render(SlipRow row, PayslipLocale locale) {
    PayslipSnapshot snapshot = parse(row);
    boolean voided = "VOIDED".equals(row.packageStatus());
    PayrollVoidReason reason = PayrollVoidReason.parse(row.voidReasonCode()).orElse(null);
    String status = voided ? "voided" : "approved";
    long started = System.nanoTime();
    PayslipPdf pdf;
    try {
      pdf =
          renderer.render(
              new PayslipPdfRenderer.Request(
                  snapshot, row.snapshotDigest(), locale, voided, reason, row.synthetic()));
    } catch (PayslipRenderException failed) {
      meters
          .counter(
              "divalhr.payslip.render",
              "locale",
              locale.code(),
              "status",
              status,
              "result",
              "failed")
          .increment();
      throw new ApiException(ErrorCode.PAYSLIP_RENDER_FAILED, Map.of());
    }
    meters
        .counter(
            "divalhr.payslip.render",
            "locale",
            locale.code(),
            "status",
            status,
            "result",
            "ok",
            "size",
            sizeBucket(pdf.size()))
        .increment();
    meters
        .timer("divalhr.payslip.render.duration", "locale", locale.code(), "status", status)
        .record(Duration.ofNanos(System.nanoTime() - started));
    return new Download(pdf, filename(row));
  }

  private static String sizeBucket(int bytes) {
    if (bytes < 16 * 1024) {
      return "lt16k";
    }
    return bytes < 64 * 1024 ? "lt64k" : "lt256k";
  }

  /** {@code payslip-<YYYY-MM>-<8 hex of the id>.pdf}: generated, never a name or a number. */
  static String filename(SlipRow row) {
    String month = StatutoryViews.month(row.periodStart());
    String id = row.id().toString().replace("-", "");
    return "payslip-" + month + "-" + id.substring(0, 8) + ".pdf";
  }

  private static CursorScope scope(
      PayrollCaller caller,
      String operation,
      String role,
      String view,
      UUID entity,
      UUID packageId,
      int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", view);
    if (entity != null) {
      filters.put("entity", entity.toString());
    }
    if (packageId != null) {
      filters.put("package", packageId.toString());
    }
    filters.put("role", role);
    filters.put("subject", caller.subject());
    filters.put("limit", Integer.toString(size));
    return new CursorScope(operation, caller.tenant(), filters);
  }
}

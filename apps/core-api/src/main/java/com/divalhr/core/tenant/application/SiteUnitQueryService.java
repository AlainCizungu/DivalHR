package com.divalhr.core.tenant.application;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.pagination.PageRequest;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.tenant.domain.SiteUnit;
import com.divalhr.core.tenant.domain.SiteUnitKind;
import com.divalhr.core.tenant.internal.JdbcSiteRepository;
import com.divalhr.core.tenant.internal.JdbcSiteUnitRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keyset-paginated department and cost-center reads for one site. Every query carries the verified
 * tenant; cursors are bound to operation, tenant and site.
 *
 * <p>Check order: parameter format ({@code VALIDATION_FAILED}), then cursor ({@code
 * CURSOR_INVALID}), then site existence ({@code SITE_NOT_FOUND}). None of these depends on another
 * tenant's data.
 */
@Service
public class SiteUnitQueryService {

  private final HierarchyValidator validator;
  private final CursorCodec cursors;
  private final JdbcSiteRepository sites;
  private final JdbcSiteUnitRepository units;
  private final OperationMetrics metrics;

  /**
   * Creates the service.
   *
   * @param validator parameter validator
   * @param cursors cursor codec
   * @param sites site repository
   * @param units department and cost-center repository
   * @param metrics operation metrics
   */
  public SiteUnitQueryService(
      HierarchyValidator validator,
      CursorCodec cursors,
      JdbcSiteRepository sites,
      JdbcSiteUnitRepository units,
      OperationMetrics metrics) {
    this.validator = validator;
    this.cursors = cursors;
    this.sites = sites;
    this.units = units;
    this.metrics = metrics;
  }

  /**
   * A page of units plus the cursor for the next page.
   *
   * @param rows rows in (code, id) byte order
   * @param nextCursor cursor, or {@code null} on the last page
   */
  public record Page(List<SiteUnit> rows, String nextCursor) {

    /** Defensively copies the rows. */
    public Page {
      rows = List.copyOf(rows);
    }
  }

  /**
   * Lists the departments or cost centers of one of the tenant's sites.
   *
   * @param kind which aggregate
   * @param tenant verified tenant
   * @param siteId raw site id
   * @param cursor opaque cursor or {@code null}
   * @param limit raw limit or {@code null}
   * @return one page
   */
  @Transactional(readOnly = true)
  public Page list(SiteUnitKind kind, TenantId tenant, String siteId, String cursor, String limit) {
    String operation = kind.listOperation();
    try {
      UUID site = validator.siteUnitListParent(siteId, limit);
      PageRequest page = PageRequest.parse(limit);
      CursorScope scope = new CursorScope(operation, tenant, Map.of("siteId", site.toString()));
      KeysetPosition after = cursor == null ? null : cursors.decode(cursor, scope);
      if (!sites.exists(tenant, site)) {
        throw CreateSiteUnitService.siteNotFound();
      }
      List<SiteUnit> rows = units.page(tenant, kind, site, after, page.limit() + 1);
      List<SiteUnit> shown = rows.subList(0, Math.min(rows.size(), page.limit()));
      String next = null;
      if (rows.size() > page.limit()) {
        SiteUnit last = shown.get(shown.size() - 1);
        next = cursors.encode(scope, new KeysetPosition(last.code(), last.id()));
      }
      metrics.record(operation, Outcome.LISTED);
      return new Page(shown, next);
    } catch (ApiException rejected) {
      metrics.record(
          operation,
          switch (rejected.code()) {
            case SITE_NOT_FOUND -> Outcome.NOT_FOUND;
            case INTERNAL_ERROR -> Outcome.FAILURE;
            default -> Outcome.VALIDATION_FAILED;
          });
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      throw failure;
    }
  }
}

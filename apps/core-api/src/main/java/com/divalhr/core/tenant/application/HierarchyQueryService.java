package com.divalhr.core.tenant.application;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.pagination.PageRequest;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.tenant.api.LegalEntityPage;
import com.divalhr.core.tenant.api.LegalEntityResponse;
import com.divalhr.core.tenant.api.SitePage;
import com.divalhr.core.tenant.api.SiteResponse;
import com.divalhr.core.tenant.domain.LegalEntity;
import com.divalhr.core.tenant.domain.Site;
import com.divalhr.core.tenant.internal.JdbcLegalEntityRepository;
import com.divalhr.core.tenant.internal.JdbcSiteRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keyset-paginated hierarchy reads. Every query carries the verified tenant; cursors are bound to
 * operation, tenant and filters.
 *
 * <p>Check order: parameter format ({@code VALIDATION_FAILED}), then cursor ({@code
 * CURSOR_INVALID}), then parent existence ({@code LEGAL_ENTITY_NOT_FOUND}). None of these depends
 * on another tenant's data, so the order leaks nothing.
 */
@Service
public class HierarchyQueryService {

  /** List operation for legal entities (metrics, cursor binding). */
  public static final String LIST_LEGAL_ENTITIES = "legal-entity.list";

  /** List operation for sites (metrics, cursor binding). */
  public static final String LIST_SITES = "site.list";

  private final HierarchyValidator validator;
  private final CursorCodec cursors;
  private final JdbcLegalEntityRepository legalEntities;
  private final JdbcSiteRepository sites;
  private final OperationMetrics metrics;

  /**
   * Creates the service.
   *
   * @param validator parameter validator
   * @param cursors cursor codec
   * @param legalEntities legal-entity repository
   * @param sites site repository
   * @param metrics operation metrics
   */
  public HierarchyQueryService(
      HierarchyValidator validator,
      CursorCodec cursors,
      JdbcLegalEntityRepository legalEntities,
      JdbcSiteRepository sites,
      OperationMetrics metrics) {
    this.validator = validator;
    this.cursors = cursors;
    this.legalEntities = legalEntities;
    this.sites = sites;
    this.metrics = metrics;
  }

  /**
   * Lists the tenant's legal entities.
   *
   * @param tenant verified tenant
   * @param cursor opaque cursor or {@code null}
   * @param limit raw limit or {@code null}
   * @return one page
   */
  @Transactional(readOnly = true)
  public LegalEntityPage legalEntities(TenantId tenant, String cursor, String limit) {
    return recorded(
        LIST_LEGAL_ENTITIES,
        () -> {
          PageRequest page = validator.page(limit);
          CursorScope scope = new CursorScope(LIST_LEGAL_ENTITIES, tenant, Map.of());
          KeysetPosition after = cursor == null ? null : cursors.decode(cursor, scope);
          List<LegalEntity> rows = legalEntities.page(tenant, after, page.limit() + 1);
          List<LegalEntity> shown = rows.subList(0, Math.min(rows.size(), page.limit()));
          String next = null;
          if (rows.size() > page.limit()) {
            LegalEntity last = shown.get(shown.size() - 1);
            next = cursors.encode(scope, new KeysetPosition(last.code(), last.id()));
          }
          return new LegalEntityPage(shown.stream().map(LegalEntityResponse::from).toList(), next);
        });
  }

  /**
   * Lists the sites of one of the tenant's legal entities.
   *
   * @param tenant verified tenant
   * @param legalEntityId raw parent id
   * @param cursor opaque cursor or {@code null}
   * @param limit raw limit or {@code null}
   * @return one page
   */
  @Transactional(readOnly = true)
  public SitePage sites(TenantId tenant, String legalEntityId, String cursor, String limit) {
    return recorded(
        LIST_SITES,
        () -> {
          UUID parent = validator.siteListParent(legalEntityId, limit);
          PageRequest page = PageRequest.parse(limit);
          CursorScope scope =
              new CursorScope(LIST_SITES, tenant, Map.of("legalEntityId", parent.toString()));
          KeysetPosition after = cursor == null ? null : cursors.decode(cursor, scope);
          if (!legalEntities.exists(tenant, parent)) {
            throw CreateSiteService.parentNotFound();
          }
          List<Site> rows = sites.page(tenant, parent, after, page.limit() + 1);
          List<Site> shown = rows.subList(0, Math.min(rows.size(), page.limit()));
          String next = null;
          if (rows.size() > page.limit()) {
            Site last = shown.get(shown.size() - 1);
            next = cursors.encode(scope, new KeysetPosition(last.code(), last.id()));
          }
          return new SitePage(shown.stream().map(SiteResponse::from).toList(), next);
        });
  }

  private <T> T recorded(String operation, Supplier<T> query) {
    try {
      T result = query.get();
      metrics.record(operation, Outcome.LISTED);
      return result;
    } catch (ApiException rejected) {
      metrics.record(
          operation,
          switch (rejected.code()) {
            case LEGAL_ENTITY_NOT_FOUND, SITE_NOT_FOUND -> Outcome.NOT_FOUND;
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

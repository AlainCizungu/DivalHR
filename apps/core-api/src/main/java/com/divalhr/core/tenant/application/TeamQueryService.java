package com.divalhr.core.tenant.application;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.pagination.PageRequest;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.tenant.api.TeamPage;
import com.divalhr.core.tenant.api.TeamResponse;
import com.divalhr.core.tenant.domain.Team;
import com.divalhr.core.tenant.internal.JdbcTeamRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keyset-paginated team listing beneath exactly one department or cost center.
 *
 * <p>Check order: parameter format and limit ({@code VALIDATION_FAILED}), parent cardinality
 * ({@code TEAM_PARENT_AMBIGUOUS} / {@code TEAM_PARENT_REQUIRED}), cursor ({@code CURSOR_INVALID}),
 * then parent existence ({@code DEPARTMENT_NOT_FOUND} / {@code COST_CENTER_NOT_FOUND}). Cursors are
 * bound to {@code team.list}, the verified tenant, the parent type and the parent id.
 */
@Service
public class TeamQueryService {

  /** List operation (metrics, cursor binding). */
  public static final String LIST_TEAMS = "team.list";

  private final HierarchyValidator validator;
  private final CursorCodec cursors;
  private final JdbcTeamRepository teams;
  private final OperationMetrics metrics;

  /**
   * Creates the service.
   *
   * @param validator parameter validator
   * @param cursors cursor codec
   * @param teams team repository
   * @param metrics operation metrics
   */
  public TeamQueryService(
      HierarchyValidator validator,
      CursorCodec cursors,
      JdbcTeamRepository teams,
      OperationMetrics metrics) {
    this.validator = validator;
    this.cursors = cursors;
    this.teams = teams;
    this.metrics = metrics;
  }

  /**
   * Lists the teams of one of the tenant's departments or cost centers.
   *
   * @param tenant verified tenant
   * @param departmentId raw department filter
   * @param costCenterId raw cost-center filter
   * @param cursor opaque cursor or {@code null}
   * @param limit raw limit or {@code null}
   * @return one page
   */
  @Transactional(readOnly = true)
  public TeamPage teams(
      TenantId tenant, String departmentId, String costCenterId, String cursor, String limit) {
    try {
      TeamParentRef parent = validator.teamListParent(departmentId, costCenterId, limit);
      PageRequest page = PageRequest.parse(limit);
      CursorScope scope =
          new CursorScope(
              LIST_TEAMS,
              tenant,
              Map.of("parentType", parent.kind().wireName(), "parentId", parent.id().toString()));
      KeysetPosition after = cursor == null ? null : cursors.decode(cursor, scope);
      if (!teams.parentExists(tenant, parent.kind(), parent.id())) {
        throw CreateTeamService.parentNotFound(parent.kind());
      }
      List<Team> rows = teams.page(tenant, parent.kind(), parent.id(), after, page.limit() + 1);
      List<Team> shown = rows.subList(0, Math.min(rows.size(), page.limit()));
      String next = null;
      if (rows.size() > page.limit()) {
        Team last = shown.get(shown.size() - 1);
        next = cursors.encode(scope, new KeysetPosition(last.code(), last.id()));
      }
      TeamPage result = new TeamPage(shown.stream().map(TeamResponse::from).toList(), next);
      metrics.record(LIST_TEAMS, Outcome.LISTED);
      return result;
    } catch (ApiException rejected) {
      metrics.record(
          LIST_TEAMS,
          switch (rejected.code()) {
            case DEPARTMENT_NOT_FOUND, COST_CENTER_NOT_FOUND -> Outcome.NOT_FOUND;
            case INTERNAL_ERROR -> Outcome.FAILURE;
            default -> Outcome.VALIDATION_FAILED;
          });
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(LIST_TEAMS, Outcome.FAILURE);
      throw failure;
    }
  }
}

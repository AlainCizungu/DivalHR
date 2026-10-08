package com.divalhr.core.people.application;

import com.divalhr.core.people.domain.EmployeeSearchKey;
import com.divalhr.core.people.internal.JdbcEmploymentScopeRepository;
import com.divalhr.core.platform.access.EmploymentExpirationScope;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * People's implementation of the {@link EmploymentExpirationScope} port (MVP-031A, Issue #73,
 * A31A-2). The search is the employee search's: an employee-number prefix or name words, accents
 * and case ignored ({@link EmployeeSearchKey}); a query with neither matches nobody (R74-1).
 */
@Component
public class PeopleExpirationScope implements EmploymentExpirationScope {

  private final JdbcEmploymentScopeRepository scope;

  /**
   * Creates the adapter.
   *
   * @param scope employment scope repository
   */
  public PeopleExpirationScope(JdbcEmploymentScopeRepository scope) {
    this.scope = scope;
  }

  @Override
  public List<RelevantEmployment> relevant(TenantId tenant, LocalDate day, Filter filter) {
    String query = filter.query();
    if (query == null) {
      return scope.relevant(tenant, day, null, List.of(), false, filter.unitId());
    }
    String upper = query.toUpperCase(Locale.ROOT);
    String numberPrefix =
        EmployeeDirectoryService.EMPLOYEE_NUMBER.matcher(upper).matches() ? upper : null;
    List<String> words = EmployeeSearchKey.words(query);
    if (numberPrefix == null && words.isEmpty()) {
      // R74-1: a supplied query with no searchable term (such as "--") matches nobody, as in the
      // employee directory; it is never treated as an absent query.
      return List.of();
    }
    return scope.relevant(tenant, day, numberPrefix, words, true, filter.unitId());
  }

  @Override
  public Map<UUID, EmployeeLabel> employees(TenantId tenant, Set<UUID> employeeIds) {
    return scope.labels(tenant, employeeIds);
  }

  @Override
  public Map<UUID, UUID> units(TenantId tenant, LocalDate day, Set<UUID> employmentIds) {
    return scope.units(tenant, day, employmentIds);
  }
}

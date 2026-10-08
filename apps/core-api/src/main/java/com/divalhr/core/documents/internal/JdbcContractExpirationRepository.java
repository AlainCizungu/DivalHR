package com.divalhr.core.documents.internal;

import com.divalhr.core.documents.domain.ExpirationCategory;
import com.divalhr.core.platform.access.EmploymentExpirationScope.RelevantEmployment;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The contract expiration queue (MVP-031A, Issue #73): the one SQL definition of the coverage head
 * (A31A-1) and of eligibility (R2 to R4), shared by the page and the counts so both always apply
 * the same rules (SQL in {@link ContractExpirationStatements}). Employments come from the people
 * port (A31A-2) as parameters; this repository names documents tables only.
 *
 * <p>Coverage head of an employment, over its non-void contracts (which never overlap, so they are
 * totally ordered by start date):
 *
 * <ol>
 *   <li>the anchor is the latest contract started on or before {@code T}, else the earliest future
 *       one;
 *   <li>from the anchor, successors are followed only while coverage is contiguous ({@code
 *       successor.start = current.end + 1}): a contiguous run is a <em>chain</em>;
 *   <li>the head is the last contract of the anchor's chain.
 * </ol>
 *
 * Earlier contracts never count once a later one has started, a future successor after a gap never
 * hides the anchor's chain, and there is at most one head per employment. An open-ended head is not
 * eligible; an eligible head ends no more than 90 days after {@code T}, and a recorded separation
 * whose last day is on or before the head's end removes it.
 */
@Repository
public class JdbcContractExpirationRepository {

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc named-parameter JDBC
   */
  public JdbcContractExpirationRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * An eligible coverage head.
   *
   * @param contractId contract
   * @param employmentId its employment
   * @param employeeId its employee
   * @param endDate its end date
   * @param days {@code end - T} (negative when overdue)
   */
  public record Head(
      UUID contractId, UUID employmentId, UUID employeeId, LocalDate endDate, int days) {}

  /**
   * Eligible heads per category.
   *
   * @param expired {@code d < 0}
   * @param next30Days {@code 0..30}
   * @param days31To60 {@code 31..60}
   * @param days61To90 {@code 61..90}
   */
  public record Counts(long expired, long next30Days, long days31To60, long days61To90) {

    /** No eligible head. */
    public static final Counts NONE = new Counts(0, 0, 0, 0);

    /**
     * Every eligible head.
     *
     * @return the sum
     */
    public long total() {
      return expired + next30Days + days31To60 + days61To90;
    }
  }

  /**
   * Eligible heads per category, over the relevant employments.
   *
   * @param tenant verified tenant
   * @param scope relevant employments (people port)
   * @param asOf business date {@code T}
   * @return the counts
   */
  public Counts counts(TenantId tenant, Collection<RelevantEmployment> scope, LocalDate asOf) {
    if (scope.isEmpty()) {
      return Counts.NONE;
    }
    return jdbc.queryForObject(
        ContractExpirationStatements.COUNTS,
        params(tenant, scope, asOf),
        (rs, row) ->
            new Counts(
                rs.getLong("expired"), rs.getLong("next30"), rs.getLong("d60"), rs.getLong("d90")));
  }

  /**
   * A page of eligible heads, most urgent first: end date, then contract ID (both immutable).
   *
   * @param tenant verified tenant
   * @param scope relevant employments (people port)
   * @param asOf business date {@code T}
   * @param categories categories to return (never empty)
   * @param afterEnd keyset end date, or {@code null}
   * @param afterId keyset contract ID, or {@code null}
   * @param limit rows to fetch
   * @return heads in queue order
   */
  public List<Head> page(
      TenantId tenant,
      Collection<RelevantEmployment> scope,
      LocalDate asOf,
      Collection<ExpirationCategory> categories,
      LocalDate afterEnd,
      UUID afterId,
      int limit) {
    if (scope.isEmpty() || categories.isEmpty()) {
      return List.of();
    }
    MapSqlParameterSource params = params(tenant, scope, asOf).addValue("limit", limit);
    boolean keyset = afterEnd != null && afterId != null;
    if (keyset) {
      params.addValue("afterEnd", afterEnd).addValue("afterId", afterId);
    }
    String sql = ContractExpirationStatements.page(categories, keyset);
    return jdbc.query(
        sql,
        params,
        (rs, row) ->
            new Head(
                rs.getObject("id", UUID.class),
                rs.getObject("employment_id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getInt("days")));
  }

  /**
   * The end date of a contract of the tenant (the immutable keyset of a cursor).
   *
   * @param tenant verified tenant
   * @param contractId contract
   * @return its end date, or empty when it is not a contract of the tenant or is open-ended
   */
  public Optional<LocalDate> endDate(TenantId tenant, UUID contractId) {
    return jdbc
        .query(
            "SELECT end_date FROM documents.contract WHERE tenant_id = :tenant AND id = :id",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("id", contractId),
            (rs, row) -> Optional.ofNullable(rs.getObject("end_date", LocalDate.class)))
        .stream()
        .findFirst()
        .flatMap(found -> found);
  }

  private static MapSqlParameterSource params(
      TenantId tenant, Collection<RelevantEmployment> scope, LocalDate asOf) {
    String[] ids = new String[scope.size()];
    String[] lastDays = new String[scope.size()];
    int i = 0;
    for (RelevantEmployment employment : scope) {
      ids[i] = employment.employmentId().toString();
      lastDays[i] = employment.lastDay() == null ? null : employment.lastDay().toString();
      i++;
    }
    return new MapSqlParameterSource()
        .addValue("tenant", tenant.value())
        .addValue("asOf", asOf)
        .addValue("employmentIds", ids)
        .addValue("lastDays", lastDays);
  }
}

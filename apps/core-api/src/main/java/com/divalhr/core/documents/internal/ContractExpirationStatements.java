package com.divalhr.core.documents.internal;

import com.divalhr.core.documents.domain.ExpirationCategory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The SQL of the contract expiration queue (MVP-031A, Issue #73), shared by {@link
 * JdbcContractExpirationRepository} and the query-plan test (A31A-5), so the test explains exactly
 * the statements that run. Every statement is bound to {@code :tenant}, the verified tenant.
 *
 * <p>Parameters: {@code tenant}, {@code asOf}, {@code employmentIds} and {@code lastDays} (text
 * arrays of equal length), and for a page {@code limit} and, with a keyset, {@code afterEnd} and
 * {@code afterId}.
 */
public final class ContractExpirationStatements {

  private static final String ELIGIBLE =
      "WITH scope(employment_id, last_day) AS ("
          + " SELECT * FROM unnest(CAST(:employmentIds AS uuid[]), CAST(:lastDays AS date[]))),"
          // Non-void contracts of the relevant employments, read in index order.
          + " ordered AS ("
          + " SELECT c.id, c.employment_id, c.employee_id, c.start_date, c.end_date, s.last_day,"
          + " lag(c.end_date) OVER w AS prev_end"
          + " FROM scope s JOIN documents.contract c ON c.tenant_id = :tenant"
          + " AND c.employment_id = s.employment_id AND c.state <> 'VOID'"
          + " WINDOW w AS (PARTITION BY c.employment_id ORDER BY c.start_date)),"
          // chain: contiguous runs; anchor: latest started, else earliest future.
          + " chained AS ("
          + " SELECT o.*, sum(CASE WHEN o.start_date = o.prev_end + 1 THEN 0 ELSE 1 END)"
          + " OVER w AS chain,"
          + " COALESCE(max(o.start_date) FILTER (WHERE o.start_date <= :asOf) OVER p,"
          + " min(o.start_date) OVER p) AS anchor_start"
          + " FROM ordered o WINDOW p AS (PARTITION BY o.employment_id),"
          + " w AS (PARTITION BY o.employment_id ORDER BY o.start_date)),"
          + " marked AS ("
          + " SELECT k.*, max(k.chain) FILTER (WHERE k.start_date = k.anchor_start) OVER p"
          + " AS anchor_chain, lead(k.chain) OVER w AS next_chain"
          + " FROM chained k WINDOW p AS (PARTITION BY k.employment_id),"
          + " w AS (PARTITION BY k.employment_id ORDER BY k.start_date)),"
          // The head: the last contract of the anchor's chain (one per employment).
          + " eligible AS ("
          + " SELECT m.id, m.employment_id, m.employee_id, m.end_date,"
          + " (m.end_date - CAST(:asOf AS date)) AS days"
          + " FROM marked m"
          + " WHERE m.chain = m.anchor_chain AND m.next_chain IS DISTINCT FROM m.chain"
          + " AND m.end_date IS NOT NULL"
          + " AND m.end_date - CAST(:asOf AS date) <= "
          + ExpirationCategory.WINDOW_DAYS
          + " AND (m.last_day IS NULL OR m.last_day > m.end_date))";

  /** The counts over the eligible heads, per category. */
  public static final String COUNTS =
      ELIGIBLE
          + " SELECT count(*) FILTER (WHERE "
          + range(ExpirationCategory.EXPIRED)
          + ") AS expired, count(*) FILTER (WHERE "
          + range(ExpirationCategory.NEXT_30_DAYS)
          + ") AS next30, count(*) FILTER (WHERE "
          + range(ExpirationCategory.DAYS_31_TO_60)
          + ") AS d60, count(*) FILTER (WHERE "
          + range(ExpirationCategory.DAYS_61_TO_90)
          + ") AS d90 FROM eligible";

  /**
   * The page statement (exposed so the query-plan test explains exactly what runs). Parameters:
   * {@code tenant}, {@code asOf}, {@code employmentIds} and {@code lastDays} (text arrays), {@code
   * limit}, and with a keyset {@code afterEnd} and {@code afterId}.
   *
   * @param categories categories to return (never empty)
   * @param keyset whether the page continues after a row
   * @return SQL
   */
  public static String page(Collection<ExpirationCategory> categories, boolean keyset) {
    List<String> ranges = new ArrayList<>();
    for (ExpirationCategory category : ExpirationCategory.values()) {
      if (categories.contains(category)) {
        ranges.add(range(category));
      }
    }
    StringBuilder sql =
        new StringBuilder(ELIGIBLE)
            .append(" SELECT id, employment_id, employee_id, end_date, days FROM eligible WHERE (")
            .append(String.join(" OR ", ranges))
            .append(')');
    if (keyset) {
      sql.append(" AND (end_date, id) > (CAST(:afterEnd AS date), CAST(:afterId AS uuid))");
    }
    return sql.append(" ORDER BY end_date, id LIMIT :limit").toString();
  }

  /** The SQL range of a category over {@code days} (constants only, never input). */
  private static String range(ExpirationCategory category) {
    return category == ExpirationCategory.EXPIRED
        ? "days < 0"
        : "days BETWEEN " + category.fromDays() + " AND " + category.toDays();
  }

  private ContractExpirationStatements() {}
}

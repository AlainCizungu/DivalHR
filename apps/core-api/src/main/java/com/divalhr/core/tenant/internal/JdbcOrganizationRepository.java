package com.divalhr.core.tenant.internal;

import com.divalhr.core.platform.tenancy.OrganizationDirectory;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.tenant.domain.Organization;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The only code that touches the tenant module's private tables. */
@Repository
public class JdbcOrganizationRepository {

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcOrganizationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Whether the verified tenant has an organization (the tenant root).
   *
   * @param tenant verified tenant
   * @return true when the organization exists
   */
  public boolean exists(TenantId tenant) {
    return jdbc.sql("SELECT 1 FROM tenant.organization WHERE id = :tenant")
        .param("tenant", tenant.value())
        .query(Integer.class)
        .optional()
        .isPresent();
  }

  /**
   * Display data of the verified tenant's organization (for the {@code OrganizationDirectory}
   * port).
   *
   * @param tenant verified tenant
   * @return name, default locale and time zone, if the organization exists
   */
  public Optional<OrganizationDirectory.OrganizationSummary> summary(TenantId tenant) {
    return jdbc.sql(
            "SELECT name, default_locale, timezone FROM tenant.organization WHERE id = :tenant")
        .param("tenant", tenant.value())
        .query(
            (rs, row) ->
                new OrganizationDirectory.OrganizationSummary(
                    tenant,
                    rs.getString("name"),
                    rs.getString("default_locale"),
                    rs.getString("timezone")))
        .optional();
  }

  /**
   * Locks an active organization for tenant administration (MVP-014, the shared lock of every
   * tenant-admin creation path; see {@code OrganizationDirectory#lockForTenantAdministration}).
   * {@code FOR NO KEY UPDATE} serializes these paths without blocking foreign-key checks of
   * ordinary hierarchy writes. {@code lock_timeout} applies to this statement only and is restored
   * afterwards.
   *
   * @param tenant target organization
   * @param timeout maximum wait
   * @return display data when the organization exists and is active
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<OrganizationDirectory.OrganizationSummary> lockActiveForTenantAdministration(
      TenantId tenant, Duration timeout) {
    String previous =
        jdbc.sql("SELECT current_setting('lock_timeout')").query(String.class).single();
    jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
        .param("timeout", Math.max(1, timeout.toMillis()) + "ms")
        .query(String.class)
        .single();
    // On a lock timeout PostgreSQL aborts the transaction, which also discards the local setting.
    Optional<OrganizationDirectory.OrganizationSummary> organization =
        jdbc.sql(
                """
                SELECT name, default_locale, timezone FROM tenant.organization
                WHERE id = :tenant AND status = 'ACTIVE'
                FOR NO KEY UPDATE
                """)
            .param("tenant", tenant.value())
            .query(
                (rs, row) ->
                    new OrganizationDirectory.OrganizationSummary(
                        tenant,
                        rs.getString("name"),
                        rs.getString("default_locale"),
                        rs.getString("timezone")))
            .optional();
    jdbc.sql("SELECT set_config('lock_timeout', :previous, true)")
        .param("previous", previous)
        .query(String.class)
        .single();
    return organization;
  }

  /**
   * Inserts a new organization and its currencies. Platform-scoped: the organization id becomes the
   * new tenant id.
   *
   * @param organization aggregate
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(Organization organization) {
    jdbc.sql(
            """
            INSERT INTO tenant.organization
              (id, name, country_code, default_locale, timezone, status, created_at, created_by)
            VALUES (:id, :name, :country, :locale, :timezone, :status, :createdAt, :createdBy)
            """)
        .param("id", organization.id())
        .param("name", organization.name())
        .param("country", organization.countryCode())
        .param("locale", organization.defaultLocale())
        .param("timezone", organization.timezone())
        .param("status", organization.status().name())
        .param("createdAt", Timestamp.from(organization.createdAt()))
        .param("createdBy", organization.createdBy())
        .update();
    for (String currency : organization.currencies()) {
      jdbc.sql(
              """
              INSERT INTO tenant.organization_currency (organization_id, currency_code)
              VALUES (:id, :currency)
              """)
          .param("id", organization.id())
          .param("currency", currency)
          .update();
    }
  }
}

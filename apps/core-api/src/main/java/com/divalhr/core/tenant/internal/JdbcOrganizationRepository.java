package com.divalhr.core.tenant.internal;

import com.divalhr.core.tenant.domain.Organization;
import java.sql.Timestamp;
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
   * Inserts a new organization and its currencies.
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

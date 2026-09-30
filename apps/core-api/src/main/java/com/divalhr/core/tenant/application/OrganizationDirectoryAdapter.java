package com.divalhr.core.tenant.application;

import com.divalhr.core.platform.tenancy.OrganizationDirectory;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.tenant.internal.JdbcOrganizationRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The tenant module's implementation of the shared {@link OrganizationDirectory} port. */
@Component
public class OrganizationDirectoryAdapter implements OrganizationDirectory {

  private final JdbcOrganizationRepository organizations;

  /**
   * Creates the adapter.
   *
   * @param organizations organization repository
   */
  public OrganizationDirectoryAdapter(JdbcOrganizationRepository organizations) {
    this.organizations = organizations;
  }

  @Override
  public Optional<OrganizationSummary> find(TenantId tenant) {
    return organizations.summary(tenant);
  }
}

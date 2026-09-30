/**
 * Identity and access domain module.
 *
 * <p>MVP-010: invitations with tenant-scoped roles and Core-owned tenant membership. The module
 * owns the {@code identity} database schema, never reads other modules' tables and reaches the
 * tenant module only through the platform {@code OrganizationDirectory} port. Keycloak is reached
 * only through the {@code IdentityDirectory} port, implemented in {@code internal.keycloak}.
 */
package com.divalhr.core.identity;

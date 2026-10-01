package com.divalhr.core.support;

import java.security.SecureRandom;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Explicit tenant memberships for integration tests (MVP-012A, M11). The membership gate is never
 * bypassed: tests that act as a tenant user first give that subject a real membership row, exactly
 * as an accepted invitation would (without an address, like a development seed row).
 */
public final class Memberships {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static volatile JdbcTemplate jdbc;

  private Memberships() {}

  /**
   * Gives the subject a membership in the tenant with the role, when the tenant's organization
   * exists and the subject has no membership yet (a subject belongs to at most one tenant). Does
   * nothing outside a Spring integration test.
   *
   * @param tenant tenant
   * @param subject token subject
   * @param role {@code tenant-admin} or {@code employee}
   */
  public static void grant(UUID tenant, String subject, String role) {
    JdbcTemplate db = jdbc;
    if (db == null || tenant == null || subject == null || subject.isBlank()) {
      return;
    }
    byte[] lookup = new byte[32];
    RANDOM.nextBytes(lookup);
    db.update(
        """
        INSERT INTO identity.tenant_membership
          (id, tenant_id, subject, role, email_lookup, created_at)
        SELECT ?, o.id, ?, ?, ?, now() FROM tenant.organization o WHERE o.id = ?
        ON CONFLICT DO NOTHING
        """,
        UUID.randomUUID(),
        subject,
        role,
        lookup,
        tenant);
  }

  /**
   * Binds the helper to the running test's own database before each test instance and method
   * (registered by {@link IntegrationTest}). Each Spring test context has its own database, so the
   * binding follows the context of the test being executed.
   */
  public static class Binding extends AbstractTestExecutionListener {

    @Override
    public void prepareTestInstance(TestContext testContext) {
      bind(testContext);
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
      bind(testContext);
    }

    private static void bind(TestContext testContext) {
      jdbc = new JdbcTemplate(testContext.getApplicationContext().getBean(DataSource.class));
    }
  }
}

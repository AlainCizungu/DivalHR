package com.divalhr.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * MVP-012A V10 (architect decision on #37, A3, M7 and A12A-2): the membership keeps its source
 * invitation's normalized address. Existing rows are backfilled only from their own source; rows
 * whose source was purged stay NULL; a new invitation-backed membership can never end its insert
 * without that exact address, even when an older writer omits it; an unanchored address is
 * rejected; the address is immutable; V10 adds no data; and the manual rollback restores V9
 * exactly.
 */
@IntegrationTest
class MembershipEmailMigrationIntegrationTest {

  private static final String SIGNATURE =
      """
      SELECT md5(pg_get_functiondef('identity.tenant_membership_immutable'::regproc))
          || '|' || (SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ','
                                       ORDER BY conname)
                     FROM pg_constraint WHERE conrelid = 'identity.tenant_membership'::regclass)
          || '|' || (SELECT string_agg(tgname, ',' ORDER BY tgname) FROM pg_trigger
                     WHERE tgrelid = 'identity.tenant_membership'::regclass
                       AND NOT tgisinternal)
          || '|' || (SELECT string_agg(indexname || ':' || indexdef, ',' ORDER BY indexname)
                     FROM pg_indexes WHERE schemaname = 'identity')
          || '|' || (SELECT string_agg(column_name || ':' || data_type, ',' ORDER BY column_name)
                     FROM information_schema.columns
                     WHERE table_schema = 'identity' AND table_name = 'tenant_membership')
      """;

  private static final String ROWS =
      """
      SELECT (SELECT count(*) FROM identity.invitation) || '/'
          || (SELECT count(*) FROM identity.tenant_membership) || '/'
          || (SELECT count(*) FROM tenant.organization) || '/'
          || (SELECT count(*) FROM platform.audit_event) || '/'
          || (SELECT count(*) FROM platform.outbox_event)
      """;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  @Test
  void v10AnchorsTheAddressToTheSourceInvitationAndItsRollbackRestoresV9Exactly() {
    String database = "email10_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url =
          "jdbc:postgresql://"
              + postgres.getHost()
              + ":"
              + postgres.getMappedPort(5432)
              + "/"
              + database;
      migrate(url, "9");
      JdbcTemplate db =
          new JdbcTemplate(
              new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
      UUID tenant = organization(db);
      UUID otherTenant = organization(db);

      // V9 data: a membership with its source, one whose source was purged, one without source.
      UUID sourced = invitation(db, tenant, "sourced@example.test", "tenant-admin");
      UUID sourcedMembership = membership(db, tenant, "sub-sourced", "tenant-admin", sourced, null);
      UUID purged = invitation(db, tenant, "purged@example.test", "employee");
      UUID purgedMembership = membership(db, tenant, "sub-purged", "employee", purged, null);
      db.update("DELETE FROM identity.invitation WHERE id = ?", purged);
      UUID seedMembership = membership(db, tenant, "sub-seed", "employee", null, null);
      String v9 = db.queryForObject(SIGNATURE, String.class);
      String rowsBefore = db.queryForObject(ROWS, String.class);

      migrate(url, "10");
      // No data added; backfill only from the row's own source invitation.
      assertThat(db.queryForObject(ROWS, String.class)).isEqualTo(rowsBefore);
      assertThat(email(db, sourcedMembership)).isEqualTo("sourced@example.test");
      assertThat(email(db, purgedMembership)).isNull();
      assertThat(email(db, seedMembership)).isNull();

      // A12A-2: an older writer that omits the address gets the exact source address.
      UUID rolling = invitation(db, tenant, "rolling@example.test", "employee");
      UUID rollingMembership = membership(db, tenant, "sub-rolling", "employee", rolling, null);
      assertThat(email(db, rollingMembership)).isEqualTo("rolling@example.test");
      // ... and a new writer must write that same address.
      UUID explicit = invitation(db, tenant, "explicit@example.test", "tenant-admin");
      UUID explicitMembership =
          membership(db, tenant, "sub-explicit", "tenant-admin", explicit, "explicit@example.test");
      assertThat(email(db, explicitMembership)).isEqualTo("explicit@example.test");
      // A different address, an unanchored address, or a foreign source are rejected.
      UUID mismatch = invitation(db, tenant, "mismatch@example.test", "employee");
      assertThatThrownBy(
              () -> membership(db, tenant, "sub-m1", "employee", mismatch, "other@example.test"))
          .hasMessageContaining("does not match its source invitation");
      assertThatThrownBy(
              () -> membership(db, tenant, "sub-m2", "employee", null, "free@example.test"))
          .hasMessageContaining("must come from its source invitation");
      UUID foreign = invitation(db, otherTenant, "foreign@example.test", "employee");
      assertThatThrownBy(() -> membership(db, tenant, "sub-m3", "employee", foreign, null))
          .hasMessageContaining("does not exist in this tenant");
      // A non-normalized spelling of the right address is not the source's address either (the
      // format check stays behind the trigger as a second line).
      assertThatThrownBy(
              () -> membership(db, tenant, "sub-m4", "employee", mismatch, "Mismatch@Example.test"))
          .hasMessageContaining("does not match its source invitation");
      // No new invitation-backed membership can end with a NULL address.
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM identity.tenant_membership"
                      + " WHERE source_invitation_id IS NOT NULL AND email IS NULL",
                  Integer.class))
          .isZero();

      // Immutable address; retention may still clear the source and keep the address.
      assertThatThrownBy(
              () ->
                  db.update(
                      "UPDATE identity.tenant_membership SET email = 'x@example.test' WHERE id = ?",
                      sourcedMembership))
          .hasMessageContaining("tenant memberships are immutable");
      assertThatThrownBy(
              () ->
                  db.update(
                      "UPDATE identity.tenant_membership SET email = NULL WHERE id = ?",
                      sourcedMembership))
          .hasMessageContaining("tenant memberships are immutable");
      db.update("DELETE FROM identity.invitation WHERE id = ?", rolling);
      assertThat(
              db.queryForObject(
                  "SELECT source_invitation_id IS NULL AND email = 'rolling@example.test'"
                      + " FROM identity.tenant_membership WHERE id = ?",
                  Boolean.class,
                  rollingMembership))
          .isTrue();

      // Rollback restores V9 exactly and keeps every membership.
      Integer memberships =
          db.queryForObject("SELECT count(*) FROM identity.tenant_membership", Integer.class);
      db.execute(read("db/rollback/V10__rollback.sql"));
      assertThat(db.queryForObject(SIGNATURE, String.class)).isEqualTo(v9);
      assertThat(
              db.queryForObject("SELECT count(*) FROM identity.tenant_membership", Integer.class))
          .isEqualTo(memberships);
      // V10 applies again after the rollback and backfills again from surviving sources.
      db.update("DELETE FROM flyway_schema_history WHERE version = '10'");
      migrate(url, "10");
      assertThat(email(db, sourcedMembership)).isEqualTo("sourced@example.test");
      assertThat(email(db, explicitMembership)).isEqualTo("explicit@example.test");
      assertThat(email(db, rollingMembership)).isNull();
    } finally {
      jdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }
  }

  private static UUID organization(JdbcTemplate db) {
    UUID id = UUID.randomUUID();
    db.update(
        """
        INSERT INTO tenant.organization
          (id, name, country_code, default_locale, timezone, created_at, created_by)
        VALUES (?, ?, 'CD', 'fr', 'Africa/Kinshasa', now(), 'sub-email')
        """,
        id,
        "Email Org " + id);
    return id;
  }

  /** An invitation whose lookup is a stand-in derived from the address (32 bytes). */
  private static UUID invitation(JdbcTemplate db, UUID tenant, String email, String role) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO identity.invitation (id, tenant_id, email, email_lookup, role, locale, state,"
            + " token_sha256, token_issued_at, expires_at, issue_count, delivery_state,"
            + " delivery_updated_at, created_at, created_by) VALUES (?, ?, ?,"
            + " sha256(convert_to(?, 'UTF8')), ?, 'fr', 'PENDING',"
            + " decode(md5(random()::text) || md5(random()::text), 'hex'), now(),"
            + " now() + interval '1 day', 1, 'QUEUED', now(), now(), 'sub-email')",
        id,
        tenant,
        email,
        email,
        role);
    return id;
  }

  /** A membership; the lookup is the source's when there is one, otherwise a random one. */
  private static UUID membership(
      JdbcTemplate db, UUID tenant, String subject, String role, UUID source, String email) {
    UUID id = UUID.randomUUID();
    String lookup =
        source == null
            ? "decode(md5(random()::text) || md5(random()::text), 'hex')"
            : "(SELECT email_lookup FROM identity.invitation WHERE id = '" + source + "')";
    String columns = email == null ? "" : ", email";
    String value = email == null ? "" : ", '" + email + "'";
    db.update(
        "INSERT INTO identity.tenant_membership (id, tenant_id, subject, role, email_lookup,"
            + " source_invitation_id, created_at"
            + columns
            + ") VALUES (?, ?, ?, ?, "
            + lookup
            + ", ?, now()"
            + value
            + ")",
        id,
        tenant,
        subject,
        role,
        source);
    return id;
  }

  private static String email(JdbcTemplate db, UUID membership) {
    return db.queryForObject(
        "SELECT email FROM identity.tenant_membership WHERE id = ?", String.class, membership);
  }

  private static String read(String resource) {
    try {
      return new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private void migrate(String url, String target) {
    Flyway.configure()
        .dataSource(url, postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .target(target)
        .load()
        .migrate();
  }
}

package com.divalhr.core.identity;

import static com.divalhr.core.support.Invitations.address;
import static com.divalhr.core.support.Invitations.anonymous;
import static com.divalhr.core.support.Invitations.invite;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.support.Bootstraps;
import com.divalhr.core.support.FakeIdentityDirectory;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Invitations;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.RecordingInvitationMailer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * MVP-014 concurrency evidence (architect decision on #38): the shared organization lock across
 * every tenant-admin path (A1), its timeout, concurrent creation (A3), cross-path crossings,
 * acceptance supersession (A2), concurrent revoke and resend (A5) and fail-closed audit and outbox
 * writes (A7).
 */
@IntegrationTest
class TenantAdminBootstrapConcurrencyIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private RecordingInvitationMailer mailer;
  @Autowired private FakeIdentityDirectory directory;

  private UUID organization;
  private String platform;
  private ExecutorService pool;

  @BeforeEach
  void setUp() throws Exception {
    mailer.reset();
    directory.reset();
    organization = Hierarchy.newTenant(mvc);
    platform = Bootstraps.platform(Bootstraps.platformSubject());
    pool = Executors.newFixedThreadPool(8);
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
    // An acceptance stopped by a lock timeout legitimately stays ACCEPTING until the reconciler
    // takes it over. Park this organization's leases so the global reconciler, which other test
    // classes run directly, never picks them up.
    jdbc.update(
        "UPDATE identity.invitation SET acceptance_lease_until = now() + interval '1 day'"
            + " WHERE tenant_id = ? AND state = 'ACCEPTING'",
        organization);
    jdbc.execute("DROP TRIGGER IF EXISTS test_fail_bootstrap_audit ON platform.audit_event");
    jdbc.execute("DROP FUNCTION IF EXISTS platform.test_fail_bootstrap_audit()");
  }

  private MockHttpServletResponse perform(RequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn().getResponse();
  }

  private RequestBuilder create(String key, String email) {
    return Bootstraps.create(platform, organization, key, Bootstraps.body(email, "fr"));
  }

  private int invitations(String where) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM identity.invitation WHERE tenant_id = ? AND " + where,
            Integer.class,
            organization);
    return count == null ? 0 : count;
  }

  /** Holds the organization's tenant-administration lock in another transaction. */
  private final class HeldLock {
    private final Connection connection;

    HeldLock() throws java.sql.SQLException {
      connection = dataSource.getConnection();
      connection.setAutoCommit(false);
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT 1 FROM tenant.organization WHERE id = ? FOR NO KEY UPDATE")) {
        statement.setObject(1, organization);
        try (ResultSet locked = statement.executeQuery()) {
          if (!locked.next()) {
            throw new IllegalStateException("organization row not found");
          }
        }
      }
    }

    void release() throws java.sql.SQLException {
      connection.rollback();
      connection.close();
    }
  }

  private static void assertBlocked(Future<?> future) throws Exception {
    try {
      future.get(700, TimeUnit.MILLISECONDS);
      throw new AssertionError("the operation did not wait for the organization lock");
    } catch (TimeoutException waiting) {
      // Expected: it waits for the shared lock.
    }
  }

  // --- A1: one lock for every tenant-admin path ---------------------------------------------

  @Test
  void everyTenantAdminPathWaitsForTheSameOrganizationLockAndEmployeesDoNot() throws Exception {
    String adminEmail = address("acceptance");
    invite(mvc, organization, adminEmail, "tenant-admin");
    String adminToken = mailer.lastTokenFor(adminEmail).orElseThrow();
    List<Future<MockHttpServletResponse>> blocked = new ArrayList<>();
    HeldLock lock = new HeldLock();
    try {
      // Employees never take the organization lock.
      assertThat(
              pool.submit(
                      () ->
                          perform(
                              Invitations.create(
                                  Hierarchy.admin(organization),
                                  Organizations.newKey(),
                                  Invitations.body(address("employee"), "employee", "fr"))))
                  .get(2, TimeUnit.SECONDS)
                  .getStatus())
          .isEqualTo(201);
      blocked.add(
          pool.submit(
              () ->
                  perform(
                      Invitations.create(
                          Hierarchy.admin(organization),
                          Organizations.newKey(),
                          Invitations.body(address("admin"), "tenant-admin", "fr")))));
      blocked.add(pool.submit(() -> perform(create(Organizations.newKey(), address("first")))));
      blocked.add(pool.submit(() -> perform(anonymous("accept", adminToken))));
      for (Future<MockHttpServletResponse> future : blocked) {
        assertBlocked(future);
      }
    } finally {
      lock.release();
    }
    // Released: the tenant-origin invitation and the acceptance complete; the bootstrap, which
    // re-checks under the lock, finds administrator invitations and memberships.
    assertThat(blocked.get(0).get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(201);
    assertThat(blocked.get(2).get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
    MockHttpServletResponse bootstrap = blocked.get(1).get(5, TimeUnit.SECONDS);
    assertThat(bootstrap.getStatus()).isEqualTo(409);
    assertThat(bootstrap.getContentAsString()).contains("TENANT_ADMIN_BOOTSTRAP_UNAVAILABLE");
  }

  @Test
  void bootstrapResendAndRevokeWaitForTheOrganizationLock() throws Exception {
    perform(create(Organizations.newKey(), address("first")));
    jdbc.update(
        "UPDATE identity.invitation SET token_issued_at = token_issued_at - interval '10 minutes'"
            + " WHERE tenant_id = ?",
        organization);
    Future<MockHttpServletResponse> resend;
    Future<MockHttpServletResponse> revoke;
    HeldLock lock = new HeldLock();
    try {
      resend =
          pool.submit(
              () -> perform(Bootstraps.resend(platform, organization, Organizations.newKey())));
      assertBlocked(resend);
    } finally {
      lock.release();
    }
    assertThat(resend.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
    HeldLock second = new HeldLock();
    try {
      revoke = pool.submit(() -> perform(Bootstraps.revoke(platform, organization)));
      assertBlocked(revoke);
    } finally {
      second.release();
    }
    assertThat(revoke.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(204);
  }

  @Test
  void aLockHeldTooLongFailsSafelyWithoutWritesOrEmail() throws Exception {
    String key = Organizations.newKey();
    MockHttpServletResponse response;
    HeldLock lock = new HeldLock();
    try {
      // The test lock timeout is 3 seconds.
      response =
          pool.submit(() -> perform(create(key, address("first")))).get(10, TimeUnit.SECONDS);
    } finally {
      lock.release();
    }
    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getContentAsString()).contains("INTERNAL_ERROR").doesNotContain("lock");
    assertThat(invitations("true")).isZero();
    assertThat(mailer.sent()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?",
                Integer.class,
                key))
        .isZero();
    // The same key works once the lock is free.
    assertThat(perform(create(key, address("first"))).getStatus()).isEqualTo(201);
  }

  // --- A3: concurrent creation ---------------------------------------------------------------

  @Test
  void eightConcurrentCreatesWithDifferentKeysYieldOneInvitationAndOneEmail() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      String email = address("first");
      Callable<MockHttpServletResponse> call =
          () -> {
            start.await();
            return perform(create(Organizations.newKey(), email));
          };
      futures.add(pool.submit(call));
    }
    start.countDown();
    int created = 0;
    for (Future<MockHttpServletResponse> future : futures) {
      MockHttpServletResponse response = future.get(30, TimeUnit.SECONDS);
      if (response.getStatus() == 201) {
        created++;
      } else {
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("TENANT_ADMIN_BOOTSTRAP_UNAVAILABLE");
      }
    }
    assertThat(created).isEqualTo(1);
    assertThat(invitations("origin = 'PLATFORM_BOOTSTRAP'")).isEqualTo(1);
    assertThat(mailer.sent()).hasSize(1);
  }

  @Test
  void eightConcurrentCreatesWithOneKeyReplayOneReceiptAndSendOneEmail() throws Exception {
    String key = Organizations.newKey();
    String email = address("first");
    CountDownLatch start = new CountDownLatch(1);
    List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      futures.add(
          pool.submit(
              () -> {
                start.await();
                return perform(create(key, email));
              }));
    }
    start.countDown();
    Set<String> bodies = new HashSet<>();
    for (Future<MockHttpServletResponse> future : futures) {
      MockHttpServletResponse response = future.get(30, TimeUnit.SECONDS);
      assertThat(response.getStatus()).isEqualTo(201);
      bodies.add(response.getContentAsString());
    }
    assertThat(bodies).hasSize(1);
    assertThat(invitations("true")).isEqualTo(1);
    assertThat(mailer.sent()).hasSize(1);
  }

  @Test
  void aTenantOriginAdministratorInvitationAndABootstrapNeverCrossTheirChecks() throws Exception {
    for (int round = 0; round < 5; round++) {
      organization = Hierarchy.newTenant(mvc);
      CountDownLatch start = new CountDownLatch(1);
      Future<MockHttpServletResponse> tenant =
          pool.submit(
              () -> {
                start.await();
                return perform(
                    Invitations.create(
                        Hierarchy.admin(organization),
                        Organizations.newKey(),
                        Invitations.body(address("admin"), "tenant-admin", "fr")));
              });
      Future<MockHttpServletResponse> bootstrap =
          pool.submit(
              () -> {
                start.await();
                return perform(create(Organizations.newKey(), address("first")));
              });
      start.countDown();
      assertThat(tenant.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(201);
      int bootstrapStatus = bootstrap.get(30, TimeUnit.SECONDS).getStatus();
      // Serialized by the shared lock: a bootstrap exists only if it was created before the
      // tenant-origin invitation, never after one it should have seen.
      boolean bootstrapFirst =
          Boolean.TRUE.equals(
              jdbc.queryForObject(
                  """
                  SELECT bool_or(b.created_at < t.created_at)
                  FROM identity.invitation b, identity.invitation t
                  WHERE b.tenant_id = ? AND t.tenant_id = b.tenant_id
                    AND b.origin = 'PLATFORM_BOOTSTRAP' AND t.origin = 'TENANT_ADMIN'
                  """,
                  Boolean.class,
                  organization));
      assertThat(bootstrapStatus).isEqualTo(bootstrapFirst ? 201 : 409);
    }
  }

  // --- A2: a bootstrap must still be first at acceptance -------------------------------------

  @Test
  void aBootstrapAcceptedAfterAnotherAdministratorNeverReachesTheIdentityProvider()
      throws Exception {
    String first = address("first");
    perform(create(Organizations.newKey(), first));
    String bootstrapToken = mailer.lastTokenFor(first).orElseThrow();
    UUID bootstrapId =
        jdbc.queryForObject(
            "SELECT id FROM identity.invitation WHERE tenant_id = ? AND origin ="
                + " 'PLATFORM_BOOTSTRAP'",
            UUID.class,
            organization);
    // Another legitimate administrator is accepted first.
    String other = address("other");
    invite(mvc, organization, other, "tenant-admin");
    mvc.perform(anonymous("accept", mailer.lastTokenFor(other).orElseThrow()))
        .andExpect(status().isOk());
    directory.reset();

    mvc.perform(anonymous("accept", bootstrapToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVITATION_INVALID"))
        .andExpect(jsonPath("$.params").isEmpty());
    assertThat(directory.provisionCalls()).isEmpty();
    assertSuperseded(bootstrapId);
  }

  @Test
  void anAdministratorAppearingWhileTheBootstrapIsProvisionedIsDetectedAndCompensated()
      throws Exception {
    String first = address("first");
    perform(create(Organizations.newKey(), first));
    String bootstrapToken = mailer.lastTokenFor(first).orElseThrow();
    UUID bootstrapId =
        jdbc.queryForObject(
            "SELECT id FROM identity.invitation WHERE tenant_id = ? AND origin ="
                + " 'PLATFORM_BOOTSTRAP'",
            UUID.class,
            organization);
    String other = address("other");
    invite(mvc, organization, other, "tenant-admin");
    String otherToken = mailer.lastTokenFor(other).orElseThrow();
    // Pause the bootstrap acceptance after its pre-check and before provisioning: another
    // administrator's acceptance commits in between.
    AtomicBoolean once = new AtomicBoolean();
    directory.beforeProvision(
        () -> {
          if (once.compareAndSet(false, true)) {
            try {
              mvc.perform(anonymous("accept", otherToken)).andExpect(status().isOk());
            } catch (Exception failure) {
              throw new IllegalStateException(failure);
            }
          }
        });

    mvc.perform(anonymous("accept", bootstrapToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
    assertSuperseded(bootstrapId);
    // The identity created for the bootstrap was compensated; only the other administrator stays.
    assertThat(directory.compensations()).contains(bootstrapId);
    assertThat(directory.identities()).containsOnlyKeys(other);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.tenant_membership WHERE tenant_id = ? AND role ="
                    + " 'tenant-admin'",
                Integer.class,
                organization))
        .isEqualTo(1);
  }

  private void assertSuperseded(UUID bootstrapId) {
    assertThat(
            jdbc.queryForMap(
                "SELECT state, revoked_by, token_sha256 IS NULL AS erased FROM identity.invitation"
                    + " WHERE id = ?",
                bootstrapId))
        .containsEntry("state", "REVOKED")
        .containsEntry("revoked_by", "system:bootstrap-superseded")
        .containsEntry("erased", true);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.tenant_membership WHERE source_invitation_id = ?",
                Integer.class,
                bootstrapId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT metadata->>'reason' FROM platform.audit_event WHERE resource_id = ? AND"
                    + " action = 'invitation.bootstrap-supersede'",
                String.class,
                bootstrapId))
        .isEqualTo("bootstrap_superseded");
  }

  // --- A5: concurrent revoke and resend ------------------------------------------------------

  @Test
  void revokeThenResendAndResendThenRevokeEndInOneConsistentState() throws Exception {
    // Revoke first: the resend finds nothing to reissue.
    perform(create(Organizations.newKey(), address("first")));
    ageIssue();
    assertThat(perform(Bootstraps.revoke(platform, organization)).getStatus()).isEqualTo(204);
    MockHttpServletResponse late =
        perform(Bootstraps.resend(platform, organization, Organizations.newKey()));
    assertThat(late.getStatus()).isEqualTo(404);
    assertThat(mailer.sent()).hasSize(1);

    // Resend first: the revoke then ends the reissued invitation.
    organization = Hierarchy.newTenant(mvc);
    perform(create(Organizations.newKey(), address("second")));
    ageIssue();
    assertThat(
            perform(Bootstraps.resend(platform, organization, Organizations.newKey())).getStatus())
        .isEqualTo(200);
    assertThat(perform(Bootstraps.revoke(platform, organization)).getStatus()).isEqualTo(204);
    assertThat(invitations("state = 'REVOKED' AND issue_count = 2")).isEqualTo(1);
  }

  @Test
  void concurrentRevokeAndResendNeverLeaveAnOpenOrDoublyReissuedBootstrap() throws Exception {
    for (int round = 0; round < 5; round++) {
      organization = Hierarchy.newTenant(mvc);
      perform(create(Organizations.newKey(), address("first")));
      ageIssue();
      int sentBefore = mailer.sent().size();
      CountDownLatch start = new CountDownLatch(1);
      Future<MockHttpServletResponse> resend =
          pool.submit(
              () -> {
                start.await();
                return perform(Bootstraps.resend(platform, organization, Organizations.newKey()));
              });
      Future<MockHttpServletResponse> revoke =
          pool.submit(
              () -> {
                start.await();
                return perform(Bootstraps.revoke(platform, organization));
              });
      start.countDown();
      int resent = resend.get(30, TimeUnit.SECONDS).getStatus();
      assertThat(revoke.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(204);
      assertThat(resent).isIn(200, 404);
      assertThat(invitations("state = 'REVOKED'")).isEqualTo(1);
      assertThat(invitations("state IN ('PENDING', 'ACCEPTING')")).isZero();
      assertThat(invitations("issue_count = " + (resent == 200 ? 2 : 1))).isEqualTo(1);
      assertThat(mailer.sent().size() - sentBefore).isEqualTo(resent == 200 ? 1 : 0);
    }
  }

  private void ageIssue() {
    jdbc.update(
        "UPDATE identity.invitation SET token_issued_at = token_issued_at - interval '10 minutes'"
            + " WHERE tenant_id = ? AND state = 'PENDING'",
        organization);
  }

  // --- A7: no email unless invitation, audit and outbox commit together -----------------------

  private void failBootstrapAudit() {
    jdbc.execute(
        """
        CREATE OR REPLACE FUNCTION platform.test_fail_bootstrap_audit() RETURNS trigger
            LANGUAGE plpgsql AS $$
        BEGIN
            RAISE EXCEPTION 'simulated audit failure';
        END;
        $$
        """);
    jdbc.execute(
        """
        CREATE TRIGGER test_fail_bootstrap_audit BEFORE INSERT ON platform.audit_event
            FOR EACH ROW WHEN (NEW.action LIKE 'invitation.bootstrap-%')
            EXECUTE FUNCTION platform.test_fail_bootstrap_audit()
        """);
  }

  @Test
  void createResendRevokeAndSupersessionFailClosedWhenTheAuditCannotCommit() throws Exception {
    failBootstrapAudit();
    MockHttpServletResponse create = perform(create(Organizations.newKey(), address("first")));
    assertThat(create.getStatus()).isEqualTo(500);
    assertThat(invitations("true")).isZero();
    assertThat(mailer.sent()).isEmpty();
    tearDown();
    pool = Executors.newFixedThreadPool(8);

    String first = address("first");
    perform(create(Organizations.newKey(), first));
    String bootstrapToken = mailer.lastTokenFor(first).orElseThrow();
    ageIssue();
    failBootstrapAudit();
    assertThat(
            perform(Bootstraps.resend(platform, organization, Organizations.newKey())).getStatus())
        .isEqualTo(500);
    assertThat(mailer.sent()).hasSize(1);
    assertThat(invitations("issue_count = 1 AND state = 'PENDING'")).isEqualTo(1);
    assertThat(perform(Bootstraps.revoke(platform, organization)).getStatus()).isEqualTo(500);
    assertThat(invitations("state = 'PENDING'")).isEqualTo(1);

    // Supersession: another administrator exists, and the supersession audit cannot commit. The
    // bootstrap is not revoked, nothing is provisioned, and no membership is created.
    String other = address("other");
    invite(mvc, organization, other, "tenant-admin");
    mvc.perform(anonymous("accept", mailer.lastTokenFor(other).orElseThrow()))
        .andExpect(status().isOk());
    directory.reset();
    assertThat(perform(anonymous("accept", bootstrapToken)).getStatus()).isEqualTo(500);
    assertThat(directory.provisionCalls()).isEmpty();
    assertThat(invitations("origin = 'PLATFORM_BOOTSTRAP' AND state = 'REVOKED'")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.tenant_membership WHERE tenant_id = ?",
                Integer.class,
                organization))
        .isEqualTo(1);
  }

  @Test
  void theOpenBootstrapIndexHoldsEvenWithoutTheLock() throws Exception {
    // Secondary invariant: two transactions inserting open bootstrap rows directly.
    String insert =
        """
        INSERT INTO identity.invitation
          (id, tenant_id, email, email_lookup, role, locale, state, token_sha256, token_issued_at,
           expires_at, issue_count, delivery_state, delivery_updated_at, created_at, created_by,
           origin)
        VALUES (gen_random_uuid(), ?, ?, decode(md5(random()::text) || md5(random()::text), 'hex'),
                'tenant-admin', 'fr', 'PENDING',
                decode(md5(random()::text) || md5(random()::text), 'hex'), now(),
                now() + interval '1 day', 1, 'QUEUED', now(), now(), 'sub-direct',
                'PLATFORM_BOOTSTRAP')
        """;
    try (Connection a = dataSource.getConnection();
        Connection b = dataSource.getConnection()) {
      a.setAutoCommit(false);
      b.setAutoCommit(false);
      try (PreparedStatement first = a.prepareStatement(insert)) {
        first.setObject(1, organization);
        first.setString(2, address("a"));
        first.executeUpdate();
      }
      Future<Integer> second =
          pool.submit(
              () -> {
                try (PreparedStatement statement = b.prepareStatement(insert)) {
                  statement.setObject(1, organization);
                  statement.setString(2, address("b"));
                  return statement.executeUpdate();
                }
              });
      assertBlocked(second);
      a.commit();
      try {
        second.get(5, TimeUnit.SECONDS);
        throw new AssertionError("a second open bootstrap was accepted");
      } catch (java.util.concurrent.ExecutionException rejected) {
        assertThat(rejected.getCause().getMessage()).contains("invitation_one_open_bootstrap");
      }
      b.rollback();
    }
  }
}

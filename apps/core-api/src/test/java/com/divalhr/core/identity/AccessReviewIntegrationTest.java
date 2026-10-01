package com.divalhr.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.identity.application.AccessReviewDigest;
import com.divalhr.core.identity.application.AccessReviewService;
import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Memberships;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-012B (architect decisions on #37: A4-A6, R1-R8, B1-B5): the read-only access review.
 * Authorization order, listing and keyset semantics, inherited unit views, cursor binding, tenant
 * isolation, exact lookup, summary consistency, fail-closed disclosure audit with the canonical
 * digest, per-subject rate limit, cache policy and privacy.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class AccessReviewIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String ENTRIES = "/api/v1/access-review/entries";
  private static final String LOOKUP = "/api/v1/access-review/lookup";
  private static final String SUMMARY = "/api/v1/access-review/summary";
  private static final String NO_STORE = "private, no-store";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private MeterRegistry meters;

  private UUID tenant;
  private UUID otherTenant;
  private String adminSubject;
  private String admin;

  @BeforeEach
  void organizations() throws Exception {
    tenant = Hierarchy.newTenant(mvc);
    otherTenant = Hierarchy.newTenant(mvc);
    adminSubject = "sub-review-" + UUID.randomUUID();
    admin = Hierarchy.bearer(tenant, adminSubject, "tenant-admin");
  }

  @AfterEach
  void removeAuditFailure() {
    jdbc.execute("DROP TRIGGER IF EXISTS test_fail_review_audit ON platform.audit_event");
    jdbc.execute("DROP FUNCTION IF EXISTS platform.test_fail_review_audit()");
  }

  // ------------------------------------------------------------------------------------------
  // Fixtures
  // ------------------------------------------------------------------------------------------

  /**
   * A member with a recorded address: an invitation (with the real keyed lookup) and the membership
   * it produced, granted at the given time (V10 copies the address from the source).
   */
  private UUID member(UUID owner, String email, String role, Instant grantedAt) {
    EmailAddress address = EmailAddress.parse(email).orElseThrow();
    UUID invitation = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO identity.invitation (id, tenant_id, email, email_lookup, role, locale, state,"
            + " token_sha256, token_issued_at, expires_at, issue_count, delivery_state,"
            + " delivery_updated_at, created_at, created_by) VALUES (?, ?, ?, ?, ?, 'fr',"
            + " 'PENDING', decode(md5(random()::text) || md5(random()::text), 'hex'), now(),"
            + " now() + interval '1 day', 1, 'QUEUED', now(), now(), 'sub-fixture')",
        invitation,
        owner,
        address.value(),
        lookups.of(address),
        role);
    UUID membership = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO identity.tenant_membership (id, tenant_id, subject, role, email_lookup,"
            + " source_invitation_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        membership,
        owner,
        "sub-member-" + membership,
        role,
        lookups.of(address),
        invitation,
        Timestamp.from(grantedAt));
    return membership;
  }

  private UUID member(String email, String role) {
    return member(tenant, email, role, Instant.now());
  }

  private UUID adminMembership() {
    return jdbc.queryForObject(
        "SELECT id FROM identity.tenant_membership WHERE subject = ?", UUID.class, adminSubject);
  }

  private UUID legalEntity(UUID owner) throws Exception {
    return Hierarchy.newLegalEntity(mvc, owner, Hierarchy.code("rle"), "2026-01-01", null);
  }

  private JsonNode json(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString());
  }

  private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn().getResponse();
  }

  private static MockHttpServletRequestBuilder entries(String bearer, String query) {
    return get(ENTRIES + (query.isEmpty() ? "" : "?" + query)).header("Authorization", bearer);
  }

  private static MockHttpServletRequestBuilder lookup(String bearer, String body) {
    return post(LOOKUP)
        .header("Authorization", bearer)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private static MockHttpServletRequestBuilder summary(String bearer) {
    return get(SUMMARY).header("Authorization", bearer);
  }

  private static String email(String address) {
    return "{\"email\": \"" + address + "\"}";
  }

  private int audits() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM platform.audit_event WHERE action = 'access-review.read'"
                + " AND tenant_id = ?",
            Integer.class,
            tenant);
    return count == null ? 0 : count;
  }

  private Map<String, Object> lastAudit() {
    return jdbc.queryForMap(
        "SELECT actor_subject, resource_type, resource_id, tenant_id, result, metadata::text AS"
            + " metadata, after_state_sha256 FROM platform.audit_event WHERE action ="
            + " 'access-review.read' AND tenant_id = ? ORDER BY occurred_at DESC, id DESC LIMIT 1",
        tenant);
  }

  private static List<UUID> ids(JsonNode page) {
    List<UUID> ids = new ArrayList<>();
    page.get("data").forEach(entry -> ids.add(UUID.fromString(entry.get("membershipId").asText())));
    return ids;
  }

  private static JsonNode withoutCorrelation(MockHttpServletResponse response) throws Exception {
    ObjectNode body = (ObjectNode) JSON.readTree(response.getContentAsString());
    body.remove("correlationId");
    return body;
  }

  // ------------------------------------------------------------------------------------------
  // Authorization order (12A gate, before binding) and no disclosure audit on denial
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyAnMfaTenantAdministratorMemberMayReviewAndChecksPrecedeBinding() throws Exception {
    String password =
        "Bearer "
            + TestTokens.token()
                .tenant(tenant)
                .subject(adminSubject)
                .roles(List.of("tenant-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    for (MockHttpServletRequestBuilder request :
        List.of(
            entries(password, "limit=abc&legalEntityId=x&siteId=y"),
            lookup(password, "{\"email\": "),
            summary(password))) {
      mvc.perform(request)
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("MFA_REQUIRED"))
          .andExpect(header().string("Cache-Control", NO_STORE));
    }
    String employee = Hierarchy.bearer(tenant, "sub-review-emp-" + UUID.randomUUID(), "employee");
    String platform =
        "Bearer "
            + TestTokens.token()
                .tenant(tenant)
                .subject("sub-review-pa-" + UUID.randomUUID())
                .roles(List.of("platform-admin"))
                .build();
    String nonMember =
        "Bearer "
            + TestTokens.token()
                .tenant(tenant)
                .subject("sub-review-nm-" + UUID.randomUUID())
                .roles(List.of("tenant-admin"))
                .build();
    for (String bearer : List.of(employee, platform, nonMember)) {
      for (MockHttpServletRequestBuilder request :
          List.of(entries(bearer, "limit=abc"), lookup(bearer, "{"), summary(bearer))) {
        mvc.perform(request)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
      }
    }
    String noTenant =
        "Bearer "
            + TestTokens.token()
                .tenant(null)
                .subject(adminSubject)
                .roles(List.of("tenant-admin"))
                .build();
    mvc.perform(summary(noTenant))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_MISSING"));
    assertThat(audits()).isZero();
  }

  // ------------------------------------------------------------------------------------------
  // Listing, keyset pagination and summary consistency
  // ------------------------------------------------------------------------------------------

  @Test
  void listsActiveMembersNewestFirstAcrossPagesWithEqualTimestamps() throws Exception {
    Instant same = Instant.parse("2026-09-01T10:00:00Z");
    List<UUID> created = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      created.add(member(tenant, "same" + i + "@example.cd", "employee", same));
    }
    UUID older = member(tenant, "older@example.cd", "tenant-admin", same.minusSeconds(60));
    // A pending invitation is not access.
    jdbc.update(
        "INSERT INTO identity.invitation (id, tenant_id, email, email_lookup, role, locale, state,"
            + " token_sha256, token_issued_at, expires_at, issue_count, delivery_state,"
            + " delivery_updated_at, created_at, created_by) VALUES (gen_random_uuid(), ?,"
            + " 'pending@example.cd', decode(md5('p') || md5('q'), 'hex'), 'employee', 'fr',"
            + " 'PENDING', decode(md5(random()::text) || md5(random()::text), 'hex'), now(),"
            + " now() + interval '1 day', 1, 'QUEUED', now(), now(), 'sub-fixture')",
        tenant);

    List<UUID> seen = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      MockHttpServletResponse response =
          perform(entries(admin, "limit=2" + (cursor == null ? "" : "&cursor=" + cursor)));
      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.getHeader("Cache-Control")).isEqualTo(NO_STORE);
      JsonNode page = json(response);
      seen.addAll(ids(page));
      cursor = page.has("nextCursor") ? page.get("nextCursor").asText() : null;
      pages++;
    } while (cursor != null);
    // The administrator's own membership (now) comes first; then the five, id descending; then
    // the older one. No duplicates, nothing missing, no pending invitation.
    List<UUID> five = new ArrayList<>(created);
    five.sort((a, b) -> b.toString().compareTo(a.toString()));
    List<UUID> expected = new ArrayList<>();
    expected.add(adminMembership());
    expected.addAll(five);
    expected.add(older);
    assertThat(seen).containsExactlyElementsOf(expected);
    assertThat(pages).isEqualTo(4);
    assertThat(new HashSet<>(seen)).hasSize(seen.size());

    JsonNode first = json(perform(entries(admin, "limit=50")));
    JsonNode entry = first.get("data").get(1);
    assertThat(entry.get("email").asText()).startsWith("same");
    assertThat(entry.get("role").asText()).isEqualTo("employee");
    assertThat(entry.get("directScope").get("type").asText()).isEqualTo("TENANT");
    assertThat(entry.get("effectiveScope").get("coversAllLegalEntitiesAndSites").asBoolean())
        .isTrue();
    assertThat(entry.get("matchedUnit").isNull()).isTrue();
    assertThat(entry.has("subject")).isFalse();
    // The administrator's seed-style membership has no recorded address.
    assertThat(first.get("data").get(0).get("email").isNull()).isTrue();
    assertThat(first.has("nextCursor")).isFalse();

    // Role filter and summary agree with the full listing.
    JsonNode admins = json(perform(entries(admin, "role=tenant-admin")));
    assertThat(ids(admins)).containsExactly(adminMembership(), older);
    JsonNode counts = json(perform(summary(admin)));
    assertThat(counts.get("byRole").get("tenant-admin").asInt()).isEqualTo(2);
    assertThat(counts.get("byRole").get("employee").asInt()).isEqualTo(5);
    assertThat(counts.get("byRole").size()).isEqualTo(2);
  }

  @Test
  void pageSizeAndFiltersAreValidatedWithoutEchoingValues() throws Exception {
    assertThat(json(perform(entries(admin, ""))).get("data").size()).isEqualTo(1);
    for (String query :
        List.of(
            "limit=0",
            "limit=51",
            "limit=abc",
            "role=platform-admin",
            "role=Admin-Secret",
            "legalEntityId=not-a-uuid",
            "siteId=12345")) {
      MockHttpServletResponse response = perform(entries(admin, query));
      assertThat(response.getStatus()).as(query).isEqualTo(400);
      assertThat(json(response).get("code").asText()).isEqualTo("VALIDATION_FAILED");
      assertThat(response.getContentAsString())
          .doesNotContain("Admin-Secret")
          .doesNotContain("not-a-uuid")
          .doesNotContain("12345");
      assertThat(response.getHeader("Cache-Control")).isEqualTo(NO_STORE);
    }
    UUID entity = legalEntity(tenant);
    MockHttpServletResponse both =
        perform(entries(admin, "legalEntityId=" + entity + "&siteId=" + UUID.randomUUID()));
    assertThat(both.getStatus()).isEqualTo(400);
    assertThat(json(both).get("params").get("fields").toString())
        .contains("legalEntityId")
        .contains("siteId")
        .doesNotContain(entity.toString());
    assertThat(audits()).isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------
  // Inherited unit views and tenant-bound, non-enumerating unit validation
  // ------------------------------------------------------------------------------------------

  @Test
  void legalEntityAndSiteViewsShowEveryMemberAsInheritedFromTheOrganization() throws Exception {
    UUID employee = member("lena@example.cd", "employee");
    // Units created after the memberships, one with a past period.
    UUID entity = legalEntity(tenant);
    UUID site =
        Hierarchy.newSite(mvc, tenant, entity, Hierarchy.code("rst"), "2026-01-01", "2026-06-30");
    for (String[] filter :
        List.of(
            new String[] {"legalEntityId", entity.toString(), "LEGAL_ENTITY"},
            new String[] {"siteId", site.toString(), "SITE"})) {
      JsonNode page = json(perform(entries(admin, filter[0] + "=" + filter[1] + "&limit=50")));
      // Every active member of the organization (including the hierarchy fixture's own
      // administrator), whatever the unit.
      List<UUID> everyone =
          jdbc.queryForList(
              "SELECT id FROM identity.tenant_membership WHERE tenant_id = ?", UUID.class, tenant);
      assertThat(ids(page)).containsExactlyInAnyOrderElementsOf(everyone).contains(employee);
      for (JsonNode entry : page.get("data")) {
        assertThat(entry.get("matchedUnit").get("type").asText()).isEqualTo(filter[2]);
        assertThat(entry.get("matchedUnit").get("id").asText()).isEqualTo(filter[1]);
        assertThat(entry.get("matchedUnit").get("inheritance").asText())
            .isEqualTo("INHERITED_FROM_TENANT");
        assertThat(entry.get("directScope").get("type").asText()).isEqualTo("TENANT");
      }
    }
    // Missing and foreign units are indistinguishable 404s, with no audit row.
    UUID foreignEntity = legalEntity(otherTenant);
    UUID foreignSite =
        Hierarchy.newSite(
            mvc, otherTenant, foreignEntity, Hierarchy.code("fst"), "2026-01-01", null);
    int before = audits();
    for (String[] pair :
        List.of(
            new String[] {"legalEntityId=" + UUID.randomUUID(), "legalEntityId=" + foreignEntity},
            new String[] {"siteId=" + UUID.randomUUID(), "siteId=" + foreignSite})) {
      MockHttpServletResponse missing = perform(entries(admin, pair[0]));
      MockHttpServletResponse foreign = perform(entries(admin, pair[1]));
      assertThat(missing.getStatus()).isEqualTo(404);
      assertThat(foreign.getStatus()).isEqualTo(404);
      assertThat(withoutCorrelation(foreign)).isEqualTo(withoutCorrelation(missing));
      assertThat(foreign.getContentAsString())
          .doesNotContain(foreignEntity.toString())
          .doesNotContain(foreignSite.toString());
      assertThat(foreign.getHeader("Cache-Control")).isEqualTo(NO_STORE);
    }
    assertThat(audits()).isEqualTo(before);
  }

  // ------------------------------------------------------------------------------------------
  // Cursor binding (B3)
  // ------------------------------------------------------------------------------------------

  @Test
  void cursorsAreBoundToTenantRoleUnitAndPageSize() throws Exception {
    for (int i = 0; i < 3; i++) {
      member("cursor" + i + "@example.cd", "employee");
    }
    UUID entity = legalEntity(tenant);
    String cursor = json(perform(entries(admin, "limit=1"))).get("nextCursor").asText();
    String roleCursor =
        json(perform(entries(admin, "limit=1&role=employee"))).get("nextCursor").asText();
    String otherAdminSubject = "sub-review-b-" + UUID.randomUUID();
    String otherAdmin = Hierarchy.bearer(otherTenant, otherAdminSubject, "tenant-admin");
    member(otherTenant, "b1@example.cd", "employee", Instant.now());
    int before = audits();
    List<String> misuse =
        List.of(
            "limit=2&cursor=" + cursor,
            "limit=1&role=employee&cursor=" + cursor,
            "limit=1&legalEntityId=" + entity + "&cursor=" + cursor,
            "limit=1&cursor=" + roleCursor,
            "limit=1&cursor=" + cursor.substring(0, cursor.length() - 2) + "AA",
            "limit=1&cursor=not-a-cursor");
    for (String query : misuse) {
      MockHttpServletResponse response = perform(entries(admin, query));
      assertThat(response.getStatus()).as(query).isEqualTo(400);
      assertThat(json(response).get("code").asText()).isEqualTo("CURSOR_INVALID");
      assertThat(response.getContentAsString()).doesNotContain(cursor);
    }
    MockHttpServletResponse foreign = perform(entries(otherAdmin, "limit=1&cursor=" + cursor));
    assertThat(json(foreign).get("code").asText()).isEqualTo("CURSOR_INVALID");
    // The same binding continues normally; invalid cursors were rejected before any query and
    // were not audited.
    assertThat(perform(entries(admin, "limit=1&cursor=" + cursor)).getStatus()).isEqualTo(200);
    assertThat(audits()).isEqualTo(before + 1);
  }

  @Test
  void paginationIsDuplicateFreeButNotASnapshotUnderConcurrentInserts() throws Exception {
    Instant base = Instant.parse("2026-08-01T00:00:00Z");
    List<UUID> existing = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      existing.add(member(tenant, "snap" + i + "@example.cd", "employee", base.plusSeconds(i)));
    }
    JsonNode first = json(perform(entries(admin, "limit=2")));
    // A membership granted after the first page was read (newest of all).
    UUID late = member("late@example.cd", "employee");
    List<UUID> seen = new ArrayList<>(ids(first));
    String cursor = first.get("nextCursor").asText();
    while (cursor != null) {
      JsonNode page = json(perform(entries(admin, "limit=2&cursor=" + cursor)));
      seen.addAll(ids(page));
      cursor = page.has("nextCursor") ? page.get("nextCursor").asText() : null;
    }
    assertThat(new HashSet<>(seen)).hasSize(seen.size());
    assertThat(seen).doesNotContain(late).containsAll(existing);
    // A fresh review shows it.
    assertThat(ids(json(perform(entries(admin, "limit=50"))))).contains(late);
  }

  // ------------------------------------------------------------------------------------------
  // Tenant isolation and exact lookup (D6, R7)
  // ------------------------------------------------------------------------------------------

  @Test
  void tenantsNeverSeeEachOtherEvenWithOverlappingAddresses() throws Exception {
    UUID mine = member(tenant, "shared@example.cd", "employee", Instant.now());
    UUID theirs = member(otherTenant, "shared@example.cd", "tenant-admin", Instant.now());
    UUID onlyTheirs = member(otherTenant, "theirs@example.cd", "employee", Instant.now());
    JsonNode page = json(perform(entries(admin, "limit=50")));
    assertThat(ids(page)).contains(mine).doesNotContain(theirs, onlyTheirs);
    assertThat(page.toString()).doesNotContain("theirs@example.cd");
    JsonNode found = json(perform(lookup(admin, email("shared@example.cd"))));
    assertThat(ids(found)).containsExactly(mine);
    assertThat(found.get("data").get(0).get("role").asText()).isEqualTo("employee");
    // Another tenant's member, an unknown address: the same empty page.
    MockHttpServletResponse foreign = perform(lookup(admin, email("theirs@example.cd")));
    MockHttpServletResponse unknown = perform(lookup(admin, email("nobody@example.cd")));
    assertThat(json(foreign)).isEqualTo(json(unknown));
    assertThat(json(foreign).get("data").size()).isZero();
    assertThat(foreign.getContentAsString()).doesNotContain("theirs@example.cd");
    JsonNode summary = json(perform(summary(admin)));
    assertThat(summary.get("byRole").get("tenant-admin").asInt()).isEqualTo(1);
    assertThat(summary.get("byRole").get("employee").asInt()).isEqualTo(1);
  }

  @Test
  void lookupNormalizesMatchesExactlyAndNeverEchoesTheInput() throws Exception {
    UUID lena = member("lena.kabila@example.cd", "employee");
    JsonNode found = json(perform(lookup(admin, email("  Lena.Kabila@EXAMPLE.cd "))));
    assertThat(ids(found)).containsExactly(lena);
    assertThat(found.get("data").get(0).get("email").asText()).isEqualTo("lena.kabila@example.cd");
    assertThat(found.has("nextCursor")).isFalse();
    // No partial or prefix match.
    assertThat(json(perform(lookup(admin, email("lena@example.cd")))).get("data").size()).isZero();
    assertThat(json(perform(lookup(admin, email("lena.kabila@example.c")))).get("data").size())
        .isZero();
    // A member without a recorded address is returned with email null, never the input.
    String seedAddress = "seed.member@example.cd";
    String seedSubject = "sub-review-seed-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO identity.tenant_membership (id, tenant_id, subject, role, email_lookup,"
            + " created_at) VALUES (gen_random_uuid(), ?, ?, 'employee', ?, now())",
        tenant,
        seedSubject,
        lookups.of(EmailAddress.parse(seedAddress).orElseThrow()));
    MockHttpServletResponse seed = perform(lookup(admin, email(seedAddress)));
    assertThat(json(seed).get("data").size()).isEqualTo(1);
    assertThat(json(seed).get("data").get(0).get("email").isNull()).isTrue();
    assertThat(seed.getContentAsString()).doesNotContain(seedAddress).doesNotContain(seedSubject);
    // Invalid bodies: field names only, never the value.
    for (String body :
        List.of(
            email("not an address"),
            "{}",
            "{\"email\": \"a@example.cd\", \"tenantId\": \"" + otherTenant + "\"}",
            email("x".repeat(300) + "@example.cd"))) {
      MockHttpServletResponse response = perform(lookup(admin, body));
      assertThat(response.getStatus()).as(body).isEqualTo(400);
      assertThat(json(response).get("code").asText()).isEqualTo("VALIDATION_FAILED");
      assertThat(response.getContentAsString())
          .doesNotContain("not an address")
          .doesNotContain(otherTenant.toString())
          .doesNotContain("xxxxxxxx");
      assertThat(response.getHeader("Cache-Control")).isEqualTo(NO_STORE);
    }
  }

  // ------------------------------------------------------------------------------------------
  // Fail-closed disclosure audit and canonical digest (A4, R1, R8, B1, B2)
  // ------------------------------------------------------------------------------------------

  @Test
  void everySuccessfulDisclosureIsAuditedWithItsCanonicalDigest() throws Exception {
    member("audit1@example.cd", "employee");
    member("audit2@example.cd", "employee");
    int before = audits();

    JsonNode first = json(perform(entries(admin, "limit=2&role=employee")));
    Map<String, Object> audit = lastAudit();
    assertThat(audit)
        .containsEntry("actor_subject", adminSubject)
        .containsEntry("resource_type", "access-review")
        .containsEntry("resource_id", tenant)
        .containsEntry("tenant_id", tenant)
        .containsEntry("result", "SUCCESS");
    assertThat(audit.get("after_state_sha256"))
        .isEqualTo(
            AccessReviewDigest.sha256(
                AccessReviewDigest.listText(true, first.has("nextCursor"), ids(first))));
    JsonNode metadata = JSON.readTree((String) audit.get("metadata"));
    assertThat(metadata.fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder("view", "filterKinds", "page", "resultCount", "digestVersion");
    assertThat(metadata.get("view").asText()).isEqualTo("list");
    assertThat(metadata.get("filterKinds").toString()).isEqualTo("[\"role\"]");
    assertThat(metadata.get("page").asText()).isEqualTo("first");
    assertThat(metadata.get("resultCount").asInt()).isEqualTo(2);
    assertThat(metadata.get("digestVersion").asInt()).isEqualTo(1);

    // An empty lookup is audited with the deterministic empty digest.
    perform(lookup(admin, email("nobody@example.cd")));
    assertThat(lastAudit().get("after_state_sha256"))
        .isEqualTo(AccessReviewDigest.sha256(AccessReviewDigest.lookupText(List.of())));
    // The summary hashes both roles in fixed order.
    perform(summary(admin));
    assertThat(lastAudit().get("after_state_sha256"))
        .isEqualTo(AccessReviewDigest.sha256(AccessReviewDigest.summaryText(1, 2)));
    assertThat(audits()).isEqualTo(before + 3);
    // No address, lookup input, membership or unit ID in any audit metadata.
    List<String> all =
        jdbc.queryForList(
            "SELECT metadata::text FROM platform.audit_event WHERE action = 'access-review.read'"
                + " AND tenant_id = ?",
            String.class,
            tenant);
    for (String row : all) {
      assertThat(row)
          .doesNotContain("@")
          .doesNotContain(adminMembership().toString())
          .doesNotContain("employee");
    }
  }

  private void failReviewAudit() {
    jdbc.execute(
        """
        CREATE OR REPLACE FUNCTION platform.test_fail_review_audit() RETURNS trigger
            LANGUAGE plpgsql AS $$
        BEGIN
            RAISE EXCEPTION 'simulated audit failure';
        END;
        $$
        """);
    jdbc.execute(
        """
        CREATE TRIGGER test_fail_review_audit BEFORE INSERT ON platform.audit_event
            FOR EACH ROW WHEN (NEW.action = 'access-review.read')
            EXECUTE FUNCTION platform.test_fail_review_audit()
        """);
  }

  @Test
  void noReviewDataLeavesWhenTheDisclosureAuditCannotCommit(CapturedOutput output)
      throws Exception {
    member("failclosed@example.cd", "employee");
    failReviewAudit();
    for (MockHttpServletRequestBuilder request :
        List.of(
            entries(admin, ""),
            lookup(admin, email("nobody@example.cd")),
            lookup(admin, email("failclosed@example.cd")),
            summary(admin))) {
      MockHttpServletResponse response = perform(request);
      assertThat(response.getStatus()).isEqualTo(500);
      assertThat(response.getHeader("Cache-Control")).isEqualTo(NO_STORE);
      JsonNode body = json(response);
      assertThat(body.get("code").asText()).isEqualTo("INTERNAL_ERROR");
      assertThat(body.has("data")).isFalse();
      assertThat(body.has("byRole")).isFalse();
      assertThat(response.getContentAsString())
          .doesNotContain("failclosed@example.cd")
          .doesNotContain(adminMembership().toString());
    }
    assertThat(audits()).isZero();
    assertThat(output.getAll())
        .doesNotContain("failclosed@example.cd")
        .doesNotContain("nobody@example.cd")
        .doesNotContain(adminSubject);
  }

  // ------------------------------------------------------------------------------------------
  // Per-subject rate limit (D5, R2, B5)
  // ------------------------------------------------------------------------------------------

  @Test
  void thirtyReviewRequestsPerMinutePerSubjectAcrossTheThreeOperations(CapturedOutput output)
      throws Exception {
    // Start well inside a fixed one-minute window so the window cannot roll over mid-test.
    while (LocalTime.now(ZoneOffset.UTC).getSecond() > 40) {
      Thread.sleep(500);
    }
    // Unauthorized calls by the same subject (no membership yet) do not consume the quota.
    String subject = "sub-review-rl-" + UUID.randomUUID();
    String bearer =
        "Bearer "
            + TestTokens.token()
                .tenant(tenant)
                .subject(subject)
                .roles(List.of("tenant-admin"))
                .build();
    for (int i = 0; i < 35; i++) {
      assertThat(perform(summary(bearer)).getStatus()).isEqualTo(403);
    }
    Memberships.grant(tenant, subject, "tenant-admin");
    int before = audits();
    // Authorized malformed requests consume it; the three operations share one bucket.
    for (int i = 0; i < 10; i++) {
      assertThat(perform(entries(bearer, "limit=0")).getStatus()).isEqualTo(400);
      assertThat(perform(lookup(bearer, email("rl" + i + "@example.cd"))).getStatus())
          .isEqualTo(200);
      assertThat(perform(summary(bearer)).getStatus()).isEqualTo(200);
    }
    for (MockHttpServletRequestBuilder request :
        List.of(entries(bearer, ""), lookup(bearer, email("over@example.cd")), summary(bearer))) {
      MockHttpServletResponse limited = perform(request);
      assertThat(limited.getStatus()).isEqualTo(429);
      assertThat(json(limited).get("code").asText()).isEqualTo("RATE_LIMITED");
      assertThat(json(limited).get("params").size()).isZero();
      assertThat(Integer.parseInt(limited.getHeader("Retry-After"))).isBetween(1, 60);
      assertThat(limited.getHeader("Cache-Control")).isEqualTo(NO_STORE);
      assertThat(limited.getContentAsString()).doesNotContain(subject).doesNotContain("over@");
    }
    // Rate-limited requests disclose nothing and are not audited (20 successes were).
    assertThat(audits()).isEqualTo(before + 20);
    // Another subject is unaffected.
    assertThat(perform(summary(admin)).getStatus()).isEqualTo(200);
    assertThat(output.getAll())
        .contains("rate_limited")
        .doesNotContain(subject)
        .doesNotContain("rl1@example.cd");
  }

  // ------------------------------------------------------------------------------------------
  // Observability allow-lists (B4)
  // ------------------------------------------------------------------------------------------

  @Test
  void logsAndMetricsCarryOnlyAllowListedValues(CapturedOutput output) throws Exception {
    UUID secret = member("observed.person@example.cd", "employee");
    UUID entity = legalEntity(tenant);
    String cursor = json(perform(entries(admin, "limit=1"))).get("nextCursor").asText();
    perform(entries(admin, "limit=1&cursor=" + cursor));
    perform(entries(admin, "legalEntityId=" + entity + "&role=employee"));
    perform(entries(admin, "legalEntityId=" + UUID.randomUUID()));
    perform(entries(admin, "role=bogus-role-value"));
    perform(lookup(admin, email("observed.person@example.cd")));
    perform(lookup(admin, email("not valid input")));
    perform(summary(admin));
    String logs = output.getAll();
    assertThat(logs)
        .contains("access_review")
        .doesNotContain("observed.person")
        .doesNotContain("not valid input")
        .doesNotContain("bogus-role-value")
        .doesNotContain(secret.toString())
        .doesNotContain(adminMembership().toString())
        .doesNotContain(entity.toString())
        .doesNotContain(cursor)
        .doesNotContain(adminSubject);
    Set<String> views = Set.of("list", "lookup", "summary");
    Set<String> kinds =
        Set.of("none", "role", "legal-entity", "site", "role+legal-entity", "role+site");
    Set<String> outcomes = Set.of("ok", "invalid", "not_found", "failed");
    for (Meter meter : meters.find(AccessReviewService.METRIC).meters()) {
      assertThat(views).contains(meter.getId().getTag("view"));
      assertThat(kinds).contains(meter.getId().getTag("filter_kind"));
      assertThat(outcomes).contains(meter.getId().getTag("outcome"));
      assertThat(meter.getId().getTags()).hasSize(3);
    }
  }
}

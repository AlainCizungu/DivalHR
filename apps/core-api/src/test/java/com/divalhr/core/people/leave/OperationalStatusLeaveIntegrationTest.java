package com.divalhr.core.people.leave;

import static com.divalhr.core.people.leave.LeaveWorld.ADMIN_APPROVALS;
import static com.divalhr.core.people.leave.LeaveWorld.EXCEPTIONS;
import static com.divalhr.core.people.leave.LeaveWorld.MY_APPROVALS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.leave.LeaveWorld.Person;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-100 (Issue #154, amendments C and D): the pending-leave figures of the operational status
 * agree with the approval inboxes and use the exact 48-hour threshold (strictly greater).
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class OperationalStatusLeaveIntegrationTest {

  private static final String ADMIN = "/api/v1/operations/status";
  private static final String TEAM = "/api/v1/me/team-operations/status";
  private static final Duration LIMIT = Duration.ofHours(48);
  private static final Duration MICRO = Duration.ofNanos(1_000);

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  @Test
  void thresholdIsStrictAndFiguresAgreeWithTheInboxes() throws Exception {
    try {
      clock.reset();
      LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
      Person mia = w.person("Mia", "Manager");
      Person ana = w.person("Ana", "Report");
      Person ben = w.person("Ben", "Report");
      Person cid = w.person("Cid", "Alone");
      w.manage(ana.employee(), mia.employee(), w.today);
      w.manage(ben.employee(), mia.employee(), w.today);
      String manager = w.policy("MGR", "MANAGER");
      String tenantAdmin = w.policy("ADM", "TENANT_ADMIN");

      Instant asOf = Instant.now().truncatedTo(ChronoUnit.MICROS);
      var start = w.today.plusDays(40);
      // Ana exactly at the threshold; Ben one microsecond beyond; Cid (no manager, admin route).
      clock.set(asOf.minus(LIMIT));
      w.request(ana, manager, start, start.plusDays(1));
      clock.set(asOf.minus(LIMIT).minus(MICRO));
      w.request(ben, manager, start, start.plusDays(1));
      clock.set(asOf.minus(Duration.ofHours(1)));
      w.request(cid, tenantAdmin, start, start.plusDays(1));

      clock.set(asOf);
      JsonNode team = w.expect(get(TEAM).header("Authorization", mia.bearer()), 200);
      JsonNode leave = team.get("approvals").get("leave");
      assertThat(leave.get("pending").asInt()).isEqualTo(2);
      assertThat(leave.get("overdue").asInt()).isEqualTo(1);
      JsonNode items = team.get("details").get("overdueLeave").get("items");
      assertThat(items).hasSize(1);
      assertThat(items.get(0).get("employee").get("id").asText())
          .isEqualTo(ben.employee().toString());
      assertThat(items.get(0).get("ageHours").asLong()).isEqualTo(48);

      JsonNode org = w.expect(get(ADMIN).header("Authorization", w.admin), 200);
      JsonNode all = org.get("approvals").get("leave");
      assertThat(all.get("pending").asInt()).isEqualTo(3);
      assertThat(all.get("overdue").asInt()).isEqualTo(1);

      // Consistency: Mia's pending equals her inbox; the administrator's count is the union of the
      // administrator inbox, the routing exceptions and the managers' inboxes.
      JsonNode inbox = w.expect(get(MY_APPROVALS).header("Authorization", mia.bearer()), 200);
      assertThat(inbox.get("items")).hasSize(leave.get("pending").asInt());
      JsonNode adminInbox = w.expect(get(ADMIN_APPROVALS).header("Authorization", w.admin), 200);
      JsonNode exceptions = w.expect(get(EXCEPTIONS).header("Authorization", w.admin), 200);
      assertThat(
              adminInbox.get("items").size()
                  + exceptions.get("items").size()
                  + inbox.get("items").size())
          .isEqualTo(all.get("pending").asInt());

      // A decided request leaves the count.
      w.approveAsManager(mia, inbox.get("items").get(0).get("id").asText());
      JsonNode after = w.expect(get(TEAM).header("Authorization", mia.bearer()), 200);
      assertThat(after.get("approvals").get("leave").get("pending").asInt()).isEqualTo(1);

      // The person without reports sees zeros, never the organization's counts.
      JsonNode cidTeam = w.expect(get(TEAM).header("Authorization", cid.bearer()), 200);
      assertThat(cidTeam.get("approvals").get("leave").get("pending").asInt()).isZero();
    } finally {
      clock.reset();
    }
  }
}

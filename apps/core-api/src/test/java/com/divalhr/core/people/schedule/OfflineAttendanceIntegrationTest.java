package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.AttendanceCalls.checkIn;
import static com.divalhr.core.people.schedule.AttendanceCalls.checkOut;
import static com.divalhr.core.people.schedule.AttendanceCalls.history;
import static com.divalhr.core.people.schedule.AttendanceCalls.instants;
import static com.divalhr.core.people.schedule.AttendanceCalls.offline;
import static com.divalhr.core.people.schedule.AttendanceCalls.sync;
import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.cancel;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.SubstitutionCalls.approve;
import static com.divalhr.core.people.schedule.SubstitutionCalls.fieldNames;
import static com.divalhr.core.people.schedule.SubstitutionCalls.request;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static com.divalhr.core.people.schedule.TeamAttendanceCalls.teamCheckIn;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.people.schedule.api.SyncOfflineAttendanceRequest;
import com.divalhr.core.people.schedule.application.OfflineAttendanceService;
import com.divalhr.core.people.schedule.internal.JdbcAttendanceRepository;
import com.divalhr.core.people.schedule.internal.JdbcWorkScheduleRepository;
import com.divalhr.core.people.schedule.internal.JdbcWorkScheduleSubstitutionRepository;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * MVP-061A end to end over the public API against real PostgreSQL (A61A-9 items 1 to 7, 9 to 12 and
 * 14): offline check-ins and check-outs keep the claimed device time and the server receipt apart;
 * the windows and the safeguards are exact at their bounds; a client event is applied once and
 * replayed with its original result (also through a new service instance, with nothing held in
 * memory), its payload never changing; accepted events are never overwritten or reordered;
 * eligibility is decided at receipt as in MVP-060A; only the caller's linked employee synchronizes,
 * and no refusal, log line, metric, audit record or event discloses an identifier, instant, offset,
 * subject, name or label. The subject's write limit is 10 per minute, so each person here makes at
 * most 10 commands.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class OfflineAttendanceIntegrationTest {

  private static final Duration MICRO = Duration.of(1, ChronoUnit.MICROS);
  private static final Duration HOUR = Duration.ofHours(1);
  private static final Duration DAY = Duration.ofHours(24);
  private static final Duration H72 = Duration.ofHours(72);
  private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
  private static final int KINSHASA = 60;
  private static final String IN = "CHECK_IN";
  private static final String OUT = "CHECK_OUT";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private MeterRegistry meters;
  @Autowired private JdbcWorkScheduleRepository schedules;
  @Autowired private JdbcWorkScheduleSubstitutionRepository shifts;
  @Autowired private JdbcAttendanceRepository attendance;
  @Autowired private EmployeeAccessLinks links;
  @Autowired private BusinessCalendar calendar;
  @Autowired private AuditRecorder audit;
  @Autowired private OutboxWriter outbox;
  @Autowired private OperationMetrics metrics;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private JsonMapper json;

  @AfterEach
  void resetClock() {
    clock.reset();
  }

  private static String newId() {
    return UUID.randomUUID().toString();
  }

  /** A one-shift schedule of {@code who} on the day, 08-16 local; its slot. */
  private String shiftOf(ScheduleWorld w, Person who, int inDays) throws Exception {
    return shiftOf(w, who, inDays, 8, 16);
  }

  private String shiftOf(ScheduleWorld w, Person who, int inDays, int from, int to)
      throws Exception {
    LocalDate day = w.today.plusDays(inDays);
    return slotIds(
            w,
            w.published(
                proposal(w.site(), day, day, List.of(shift("a", day, from, to, who.employee())))))
        .get(0);
  }

  private MockHttpServletRequestBuilder at(
      Person who, String client, String slot, String type, Instant occurred) throws Exception {
    return sync(who.bearer(), offline(client, slot, type, occurred, KINSHASA));
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  private int events(UUID employee) {
    return count("SELECT count(*) FROM people.attendance_event WHERE employee_id = ?", employee);
  }

  /** Every attendance event of the tenant, as stored, in a stable order. */
  private List<String> evidence(UUID tenant) {
    return jdbc.queryForList(
        "SELECT id || ' ' || record_id || ' ' || event_type || ' ' || recorded_at || ' '"
            + " || recording_method || ' ' || recorded_by || ' ' || idempotency_digest || ' '"
            + " || coalesce(client_event_id::text, '-') || ' '"
            + " || coalesce(device_offset_minutes::text, '-') || ' '"
            + " || coalesce(received_at::text, '-') FROM people.attendance_event"
            + " WHERE tenant_id = ? ORDER BY id",
        String.class,
        tenant);
  }

  private static String logs(CapturedOutput output) {
    return String.join(
        "\n", output.getAll().lines().filter(line -> line.startsWith("{\"@timestamp")).toList());
  }

  /** A problem without its per-request fields. */
  private static JsonNode stable(JsonNode problem) {
    com.fasterxml.jackson.databind.node.ObjectNode copy =
        (com.fasterxml.jackson.databind.node.ObjectNode) problem.deepCopy();
    copy.remove("correlationId");
    copy.remove("instance");
    return copy;
  }

  // ------------------------------------------------------------------------------------------
  // 1, 6, 13, 14: the journey, replays (also with nothing in memory), audit, events and privacy
  // ------------------------------------------------------------------------------------------

  @Test
  void anOfflineCheckInAndCheckOutKeepTheClaimedTimeAndTheReceiptApart(CapturedOutput output)
      throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    String slot = shiftOf(w, ana, 3);
    Instant[] shift = instants(jdbc, slot);
    Instant claimedIn = shift[0].minus(Duration.ofMinutes(10)).plus(Duration.ofNanos(123_456_000));
    Instant receivedIn = shift[0].plus(Duration.ofHours(3));
    String client = newId();
    clock.set(receivedIn);

    MockHttpServletResponse created = w.call(at(ana, client, slot, IN, claimedIn));
    assertThat(created.getStatus()).isEqualTo(201);
    assertThat(created.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode result = JSON.readTree(created.getContentAsString());
    assertThat(fieldNames(result))
        .containsExactly(
            "outcome",
            "clientEventId",
            "attendanceRecordId",
            "attendanceEventId",
            "type",
            "deviceOccurredAt",
            "serverReceivedAt",
            "recordingMethod",
            "recordStatus");
    assertThat(result.get("outcome").asText()).isEqualTo("APPLIED");
    assertThat(result.get("clientEventId").asText()).isEqualTo(client);
    assertThat(result.get("type").asText()).isEqualTo(IN);
    assertThat(Instant.parse(result.get("deviceOccurredAt").asText())).isEqualTo(claimedIn);
    assertThat(Instant.parse(result.get("serverReceivedAt").asText())).isEqualTo(receivedIn);
    assertThat(result.get("recordingMethod").asText()).isEqualTo("EMPLOYEE_OFFLINE_SYNC");
    assertThat(result.get("recordStatus").asText()).isEqualTo("OPEN");
    String record = result.get("attendanceRecordId").asText();
    String checkInEvent = result.get("attendanceEventId").asText();

    // The evidence: the claimed time is the event's and the record's instant; the receipt, the
    // offset and the client event are provenance; the subject as recorded; a digest of the key.
    Map<String, Object> event =
        jdbc.queryForMap(
            "SELECT e.recorded_at, e.received_at, e.device_offset_minutes, e.client_event_id,"
                + " e.recording_method, e.recorded_by, e.idempotency_digest, r.checked_in_at"
                + " FROM people.attendance_event e JOIN people.attendance_record r"
                + " ON r.tenant_id = e.tenant_id AND r.id = e.record_id WHERE e.id = ?::uuid",
            checkInEvent);
    assertThat(((java.sql.Timestamp) event.get("recorded_at")).toInstant()).isEqualTo(claimedIn);
    assertThat(((java.sql.Timestamp) event.get("checked_in_at")).toInstant()).isEqualTo(claimedIn);
    assertThat(((java.sql.Timestamp) event.get("received_at")).toInstant()).isEqualTo(receivedIn);
    assertThat(event.get("device_offset_minutes")).isEqualTo(KINSHASA);
    assertThat(event.get("client_event_id")).isEqualTo(UUID.fromString(client));
    assertThat(event.get("recording_method")).isEqualTo("EMPLOYEE_OFFLINE_SYNC");
    assertThat(event.get("recorded_by")).isEqualTo(ana.subject());
    assertThat((String) event.get("idempotency_digest")).matches("^[0-9a-f]{64}$");

    // The employee's history shows the claimed time and the method.
    JsonNode item = w.expect(history(ana.bearer()), 200).get("items").get(0);
    assertThat(item.get("id").asText()).isEqualTo(record);
    assertThat(Instant.parse(item.get("checkedInAt").asText())).isEqualTo(claimedIn);
    assertThat(item.get("checkInRecordingMethod").asText()).isEqualTo("EMPLOYEE_OFFLINE_SYNC");
    assertThat(item.get("checkInSupervisorReason").isNull()).isTrue();

    // The identical submission: the original result, nothing written.
    String tenant = w.tenant().toString();
    List<Integer> rows = written(w.tenant());
    JsonNode replayed = w.expect(at(ana, client, slot, IN, claimedIn), 200);
    assertThat(replayed.get("outcome").asText()).isEqualTo("ALREADY_APPLIED");
    assertThat(without(replayed, "outcome")).isEqualTo(without(result, "outcome"));
    assertThat(written(w.tenant())).isEqualTo(rows);

    // With nothing held in memory: a new service instance answers from the stored evidence alone.
    OfflineAttendanceService restarted =
        new OfflineAttendanceService(
            schedules,
            shifts,
            attendance,
            links,
            calendar,
            audit,
            outbox,
            metrics,
            meters,
            transactions,
            json);
    SyncOfflineAttendanceRequest same = new SyncOfflineAttendanceRequest();
    Map<String, Object> body = offline(client, slot, IN, claimedIn, KINSHASA);
    same.setClientEventId(body.get("clientEventId"));
    same.setSlotId(body.get("slotId"));
    same.setType(body.get("type"));
    same.setDeviceOccurredAt(body.get("deviceOccurredAt"));
    same.setDeviceOffsetMinutes(body.get("deviceOffsetMinutes"));
    OfflineAttendanceService.Synced again =
        restarted.sync(
            new PeopleCaller(new TenantId(w.tenant()), ana.subject(), "restart-check"), same);
    assertThat(again.replayed()).isTrue();
    assertThat(again.result().attendanceEventId().toString()).isEqualTo(checkInEvent);
    assertThat(again.result().serverReceivedAt()).isEqualTo(receivedIn);
    assertThat(written(w.tenant())).isEqualTo(rows);

    // The check-out: claimed inside its window, received after the window closed (within 72 h).
    Instant claimedOut = shift[1].plus(Duration.ofMinutes(30));
    Instant receivedOut = shift[1].plus(Duration.ofHours(30));
    String outClient = newId();
    clock.set(receivedOut);
    JsonNode completed = w.expect(at(ana, outClient, slot, OUT, claimedOut), 201);
    assertThat(completed.get("attendanceRecordId").asText()).isEqualTo(record);
    assertThat(completed.get("recordStatus").asText()).isEqualTo("COMPLETED");
    assertThat(Instant.parse(completed.get("deviceOccurredAt").asText())).isEqualTo(claimedOut);
    assertThat(Instant.parse(completed.get("serverReceivedAt").asText())).isEqualTo(receivedOut);
    assertThat(
            jdbc.queryForObject(
                "SELECT checked_out_at FROM people.attendance_record WHERE id = ?::uuid",
                java.sql.Timestamp.class,
                record))
        .isEqualTo(java.sql.Timestamp.from(claimedOut));
    // A replay of the check-in now reports the record's current status.
    JsonNode current = w.expect(at(ana, client, slot, IN, claimedIn), 200);
    assertThat(current.get("recordStatus").asText()).isEqualTo("COMPLETED");
    assertThat(current.get("attendanceEventId").asText()).isEqualTo(checkInEvent);

    // Audit: one record per application, metadata only; never one for a replay.
    List<JsonNode> audits = new ArrayList<>();
    for (String metadata :
        jdbc.queryForList(
            "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ?::uuid"
                + " AND action = 'attendance.offline-event-sync' ORDER BY occurred_at",
            String.class,
            tenant)) {
      audits.add(JSON.readTree(metadata));
    }
    assertThat(audits).hasSize(2);
    for (JsonNode metadata : audits) {
      assertThat(new TreeSet<>(fieldNames(metadata)))
          .containsExactly("eventType", "outcome", "recordingMethod", "replay", "schemaVersion");
      assertThat(metadata.get("outcome").asText()).isEqualTo("APPLIED");
      assertThat(metadata.get("recordingMethod").asText()).isEqualTo("EMPLOYEE_OFFLINE_SYNC");
      assertThat(metadata.get("replay").asBoolean()).isFalse();
    }
    assertThat(audits.get(0).get("eventType").asText()).isEqualTo("CHECKED_IN");
    assertThat(audits.get(1).get("eventType").asText()).isEqualTo("CHECKED_OUT");
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?::uuid"
                    + " AND action = 'attendance.offline-event-sync'"
                    + " AND after_state_sha256 ~ '^[0-9a-f]{64}$'",
                tenant))
        .isEqualTo(2);

    // Events: the existing envelope, unchanged; the claimed time; never the offset.
    List<String> envelopes =
        jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                + " AND envelope ->> 'eventType' LIKE 'people.attendance.%' ORDER BY created_at",
            String.class, tenant);
    assertThat(envelopes).hasSize(2);
    JsonNode first = JSON.readTree(envelopes.get(0));
    assertThat(first.get("eventType").asText()).isEqualTo("people.attendance.checked-in.v1");
    assertThat(JSON.readTree(envelopes.get(1)).get("eventType").asText())
        .isEqualTo("people.attendance.checked-out.v1");
    assertThat(fieldNames(first.get("data")))
        .containsExactlyInAnyOrder(
            "attendanceRecordId",
            "scheduleId",
            "slotId",
            "employeeId",
            "employmentId",
            "eventType",
            "recordedAt");
    assertThat(Instant.parse(first.get("data").get("recordedAt").asText())).isEqualTo(claimedIn);
    assertThat(String.join("\n", envelopes))
        .doesNotContain("ffset")
        .doesNotContain(client)
        .doesNotContain("EMPLOYEE_OFFLINE_SYNC");

    // Logs and metrics: operation, bounded outcome, type and replay only.
    String logs = logs(output);
    List<String> synced =
        logs.lines().filter(line -> line.contains("\"attendance_offline_synced\"")).toList();
    // Four over HTTP, one through the new instance.
    assertThat(synced).hasSize(5);
    for (String line : synced) {
      JsonNode entry = JSON.readTree(line);
      assertThat(entry.get("operation").asText()).isEqualTo("attendance.offline-event-sync");
      assertThat(entry.get("outcome").asText()).isIn("applied", "replayed");
      assertThat(entry.get("type").asText()).isIn("check_in", "check_out");
      assertThat(entry.has("replay")).isTrue();
    }
    String disclosed = String.join("\n", audits.toString(), String.join("\n", envelopes), logs);
    for (String secret :
        List.of(
            ana.subject(),
            ana.number(),
            "Kalala",
            "Caissière",
            "Cashier",
            client,
            outClient,
            "+01:00",
            "Bearer ")) {
      assertThat(disclosed).as("no %s", secret).doesNotContain(secret);
    }
    for (String secret :
        List.of(
            record,
            checkInEvent,
            slot,
            claimedIn.toString(),
            receivedIn.toString(),
            claimedOut.toString())) {
      assertThat(logs).as("no %s in logs", secret).doesNotContain(secret);
    }
    Counter applied =
        meters
            .find(OfflineAttendanceService.SYNC_METRIC)
            .tag("outcome", "applied")
            .tag("type", "check_in")
            .tag("replay", "false")
            .counter();
    assertThat(applied).isNotNull();
    assertThat(applied.getId().getTags())
        .extracting(io.micrometer.core.instrument.Tag::getKey)
        .containsExactlyInAnyOrder("operation", "outcome", "type", "replay");
    assertThat(
            meters
                .find(OfflineAttendanceService.SYNC_METRIC)
                .tag("outcome", "replayed")
                .tag("replay", "true")
                .counter())
        .isNotNull();
  }

  /** Events, records, offline audit records and attendance outbox events of the tenant. */
  private List<Integer> written(UUID tenant) {
    return List.of(
        count("SELECT count(*) FROM people.attendance_event WHERE tenant_id = ?", tenant),
        count("SELECT count(*) FROM people.attendance_record WHERE tenant_id = ?", tenant),
        count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action = 'attendance.offline-event-sync'",
            tenant),
        count(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                + " AND envelope ->> 'eventType' LIKE 'people.attendance.%'",
            tenant.toString()));
  }

  private static JsonNode without(JsonNode node, String field) {
    com.fasterxml.jackson.databind.node.ObjectNode copy =
        (com.fasterxml.jackson.databind.node.ObjectNode) node.deepCopy();
    copy.remove(field);
    return copy;
  }

  // ------------------------------------------------------------------------------------------
  // 2 to 5: the windows and the safeguards, exactly at their bounds
  // ------------------------------------------------------------------------------------------

  @Test
  void theWindowsAndTheSafeguardsAreExactAtTheirBounds() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    Person cy = w.person("Cy", "Mukendi");
    Person dee = w.person("Dee", "Tshala");
    Person eve = w.person("Eve", "Banza");

    // Every schedule is published before the clock moves.
    String a = shiftOf(w, ana, 3);
    String b = shiftOf(w, ben, 4);
    String c = shiftOf(w, cy, 5);
    String e = shiftOf(w, dee, 6);
    String f = shiftOf(w, eve, 7);

    // Check-in window: from 2 hours before the start (inclusive) to the end (exclusive).
    Instant[] sa = instants(jdbc, a);
    clock.set(sa[0].plus(HOUR));
    w.problem(
        at(ana, newId(), a, IN, sa[0].minus(Duration.ofHours(2)).minus(MICRO)),
        409,
        "ATTENDANCE_CHECK_IN_TOO_EARLY");
    clock.set(sa[1]);
    w.problem(at(ana, newId(), a, IN, sa[1]), 409, "ATTENDANCE_CHECK_IN_WINDOW_CLOSED");
    clock.set(sa[0].plus(HOUR));
    w.expect(at(ana, newId(), a, IN, sa[0].minus(Duration.ofHours(2))), 201);
    assertThat(events(ana.employee())).isEqualTo(1);

    Instant[] sb = instants(jdbc, b);
    clock.set(sb[1]);
    w.expect(at(ben, newId(), b, IN, sb[1].minus(MICRO)), 201);
    // Check-out window: until 24 hours after the end, inclusive.
    Instant until = sb[1].plus(DAY);
    clock.set(until.plus(HOUR));
    w.problem(
        at(ben, newId(), b, OUT, until.plus(MICRO)), 409, "ATTENDANCE_CHECK_OUT_WINDOW_CLOSED");
    w.expect(at(ben, newId(), b, OUT, until), 201);
    assertThat(events(ben.employee())).isEqualTo(2);

    // At most 5 minutes after the receipt, for either type.
    Instant[] sc = instants(jdbc, c);
    clock.set(sc[0]);
    w.problem(
        at(cy, newId(), c, IN, sc[0].plus(FIVE_MINUTES).plus(MICRO)),
        409,
        "OFFLINE_ATTENDANCE_FUTURE_TIME");
    w.expect(at(cy, newId(), c, IN, sc[0].plus(FIVE_MINUTES)), 201);
    clock.set(sc[0].plus(HOUR));
    w.problem(
        at(cy, newId(), c, OUT, sc[0].plus(HOUR).plus(FIVE_MINUTES).plus(MICRO)),
        409,
        "OFFLINE_ATTENDANCE_FUTURE_TIME");
    w.expect(at(cy, newId(), c, OUT, sc[0].plus(HOUR).plus(FIVE_MINUTES)), 201);
    assertThat(events(cy.employee())).isEqualTo(2);

    // Received at most 72 hours after the claimed time, for either type.
    Instant[] se = instants(jdbc, e);
    Instant inAt = se[0].minus(HOUR);
    clock.set(inAt.plus(H72).plus(MICRO));
    w.problem(at(dee, newId(), e, IN, inAt), 409, "OFFLINE_ATTENDANCE_TOO_OLD");
    clock.set(inAt.plus(H72));
    w.expect(at(dee, newId(), e, IN, inAt), 201);
    Instant outAt = se[1].plus(HOUR);
    clock.set(outAt.plus(H72).plus(MICRO));
    w.problem(at(dee, newId(), e, OUT, outAt), 409, "OFFLINE_ATTENDANCE_TOO_OLD");
    clock.set(outAt.plus(H72));
    w.expect(at(dee, newId(), e, OUT, outAt), 201);
    assertThat(events(dee.employee())).isEqualTo(2);

    // Offsets: equal to the timestamp's, both bounds accepted, beyond refused.
    Instant[] sf = instants(jdbc, f);
    clock.set(sf[0].plus(HOUR));
    w.problem(
        sync(eve.bearer(), withOffset(offline(newId(), f, IN, sf[0], 840), 0)),
        422,
        "OFFLINE_ATTENDANCE_OFFSET_MISMATCH");
    w.problem(
        sync(eve.bearer(), withOffset(offline(newId(), f, IN, sf[0], 0), 841)),
        400,
        "VALIDATION_FAILED");
    w.expect(sync(eve.bearer(), offline(newId(), f, IN, sf[0], 840)), 201);
    w.expect(sync(eve.bearer(), offline(newId(), f, OUT, sf[0].plus(HOUR), -840)), 201);
    assertThat(
            jdbc.queryForList(
                "SELECT device_offset_minutes FROM people.attendance_event WHERE employee_id = ?"
                    + " ORDER BY recorded_at",
                Integer.class,
                eve.employee()))
        .containsExactly(840, -840);
  }

  private static Map<String, Object> withOffset(Map<String, Object> body, int offset) {
    Map<String, Object> changed = new HashMap<>(body);
    changed.put("deviceOffsetMinutes", offset);
    return changed;
  }

  // ------------------------------------------------------------------------------------------
  // 6, 7, 12: one application per client event; its payload never changes
  // ------------------------------------------------------------------------------------------

  @Test
  void aClientEventIsAppliedOnceAndAnyChangedFieldIsRefusedWithoutDisclosure() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    String a = shiftOf(w, ana, 3);
    String other = shiftOf(w, ana, 4);
    String bens = shiftOf(w, ben, 5);
    ScheduleWorld foreign = new ScheduleWorld(mvc, jdbc, lookups);
    Person zoe = foreign.person("Zoe", "Kanku");
    String zoes = shiftOf(foreign, zoe, 3);
    Instant[] sa = instants(jdbc, a);
    Instant claimed = sa[0].minus(HOUR);
    clock.set(sa[0]);
    String client = newId();
    String record =
        w.expect(at(ana, client, a, IN, claimed), 201).get("attendanceRecordId").asText();
    List<String> stored = evidence(w.tenant());

    List<Map<String, Object>> changed =
        List.of(
            offline(client, other, IN, claimed, KINSHASA),
            offline(client, a, OUT, claimed, KINSHASA),
            offline(client, a, IN, claimed.plus(MICRO), KINSHASA),
            // The same instant written at another (consistent) offset.
            offline(client, a, IN, claimed, 120));
    for (Map<String, Object> body : changed) {
      JsonNode refused = w.problem(sync(ana.bearer(), body), 409, "IDEMPOTENCY_KEY_REUSED");
      assertThat(refused.get("params").size()).isZero();
      assertThat(refused.toString())
          .doesNotContain(record)
          .doesNotContain(a)
          .doesNotContain(client)
          .doesNotContain(claimed.toString());
    }
    assertThat(evidence(w.tenant())).isEqualTo(stored);

    // The identity is the tenant, the employee and the client event: Ben and another tenant's
    // employee apply their own events under the same client event ID.
    clock.set(instants(jdbc, bens)[0]);
    w.expect(at(ben, client, bens, IN, instants(jdbc, bens)[0].minus(HOUR)), 201);
    clock.set(instants(jdbc, zoes)[0]);
    foreign.expect(at(zoe, client, zoes, IN, instants(jdbc, zoes)[0].minus(HOUR)), 201);

    // A replay needs the caller's current link: without it nothing is returned.
    w.unlink(ana.employee());
    MockHttpServletResponse denied = w.call(at(ana, client, a, IN, claimed));
    assertThat(denied.getStatus()).isEqualTo(403);
    assertThat(JSON.readTree(denied.getContentAsString()).get("code").asText())
        .isEqualTo("EMPLOYEE_LINK_REQUIRED");
    assertThat(denied.getContentAsString()).doesNotContain(record).doesNotContain(a);
    assertThat(events(ana.employee())).isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------
  // 9, 10: accepted events are never overwritten or reordered
  // ------------------------------------------------------------------------------------------

  @Test
  void acceptedOnlineSupervisorAndOfflineEventsAreNeverOverwrittenOrReordered() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    w.manageAll(mia, ana, ben);
    LocalDate day = w.today.plusDays(3);
    List<String> slots =
        slotIds(
            w,
            w.published(
                proposal(
                    w.site(),
                    day,
                    day,
                    List.of(
                        shift("p", day, 8, 16, ana.employee()),
                        shift("q", day, 17, 20, ana.employee()),
                        shift("v", day, 8, 16, ben.employee())))));
    String p = slots.get(0);
    String q = slots.get(1);
    String v = slots.get(2);
    String s = shiftOf(w, ana, 4);
    Instant[] sp = instants(jdbc, p);
    Instant[] sq = instants(jdbc, q);

    // Online check-in on P; an offline check-in on the same shift conflicts.
    Instant checkedIn = sp[0].minus(Duration.ofMinutes(30));
    clock.set(checkedIn);
    String record =
        w.expect(checkIn(ana, Organizations.newKey(), p), 201).get("attendanceRecordId").asText();
    // The manager records Ben's check-in on V.
    w.expect(teamCheckIn(mia, Organizations.newKey(), v), 201);
    List<String> before = evidence(w.tenant());
    clock.set(sp[0].plus(HOUR));
    w.problem(at(ana, newId(), p, IN, sp[0]), 409, "OFFLINE_ATTENDANCE_CONFLICT");
    w.problem(at(ben, newId(), v, IN, sp[0]), 409, "OFFLINE_ATTENDANCE_CONFLICT");
    // A check-out never precedes or equals the accepted check-in.
    w.problem(at(ana, newId(), p, OUT, checkedIn), 409, "OFFLINE_ATTENDANCE_CONFLICT");
    w.problem(
        at(ben, newId(), v, OUT, checkedIn.minus(Duration.ofMinutes(1))),
        409,
        "OFFLINE_ATTENDANCE_CONFLICT");
    assertThat(evidence(w.tenant())).isEqualTo(before);
    w.expect(at(ana, newId(), p, OUT, checkedIn.plus(MICRO)), 201);
    // Completed: another check-out, offline or online, is refused; nothing is replaced.
    w.problem(at(ana, newId(), p, OUT, sp[0].plus(HOUR)), 409, "OFFLINE_ATTENDANCE_CONFLICT");
    w.problem(checkOut(ana, Organizations.newKey(), record), 409, "ATTENDANCE_ALREADY_CHECKED_OUT");
    assertThat(
            jdbc.queryForObject(
                "SELECT checked_out_at FROM people.attendance_record WHERE id = ?::uuid",
                java.sql.Timestamp.class,
                record))
        .isEqualTo(java.sql.Timestamp.from(checkedIn.plus(MICRO)));

    // No check-in yet: a check-out has no record to complete (the existing exact refusal).
    w.problem(at(ana, newId(), q, OUT, sq[0]), 404, "ATTENDANCE_RECORD_NOT_FOUND");
    // Q open online; an offline check-in on S whose range meets Q's open range conflicts.
    clock.set(sq[0].minus(HOUR));
    w.expect(checkIn(ana, Organizations.newKey(), q), 201);
    Instant[] ss = instants(jdbc, s);
    clock.set(ss[0]);
    w.problem(at(ana, newId(), s, IN, ss[0].minus(HOUR)), 409, "OFFLINE_ATTENDANCE_CONFLICT");
    List<String> after = evidence(w.tenant());
    assertThat(after).containsAll(before);
    assertThat(after).hasSize(before.size() + 2);
  }

  // ------------------------------------------------------------------------------------------
  // 11: eligibility at receipt, as MVP-060A
  // ------------------------------------------------------------------------------------------

  @Test
  void eligibilityIsDecidedAtReceiptAsForAnOnlineCheckIn() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    w.manageAll(mia, ana, ben);
    LocalDate d3 = w.today.plusDays(3);
    LocalDate d4 = w.today.plusDays(4);
    LocalDate d5 = w.today.plusDays(5);
    LocalDate d6 = w.today.plusDays(6);
    String substituted =
        w.published(proposal(w.site(), d3, d3, List.of(shift("a", d3, 8, 16, ana.employee()))));
    String cancelled =
        w.published(proposal(w.site(), d4, d4, List.of(shift("a", d4, 8, 16, ana.employee()))));
    String original =
        w.published(proposal(w.site(), d5, d5, List.of(shift("a", d5, 8, 16, ana.employee()))));
    String later =
        w.published(proposal(w.site(), d6, d6, List.of(shift("a", d6, 8, 16, ana.employee()))));
    String s3 = slotIds(w, substituted).get(0);
    String s4 = slotIds(w, cancelled).get(0);
    String s5 = slotIds(w, original).get(0);
    String s6 = slotIds(w, later).get(0);
    String requestId = w.expect(request(ana, Organizations.newKey(), s3), 201).get("id").asText();
    w.expect(approve(mia, requestId, Organizations.newKey(), ben.employee()), 200);
    w.expect(cancel(w.admin, Organizations.newKey(), cancelled, "Fermeture"), 200);
    String successor =
        w.replaced(
            original, List.of(shift("a", d5, 8, 16, ana.employee())), "Nouvelle organisation");
    String s5b = slotIds(w, successor).get(0);

    // After an approved substitution the published employee has no authority; the substitute has.
    Instant[] t3 = instants(jdbc, s3);
    clock.set(t3[0].plus(HOUR));
    w.problem(at(ana, newId(), s3, IN, t3[0]), 404, "ATTENDANCE_SHIFT_NOT_FOUND");
    String bens = w.expect(at(ben, newId(), s3, IN, t3[0]), 201).get("attendanceRecordId").asText();
    assertThat(
            jdbc.queryForObject(
                "SELECT employee_id FROM people.attendance_record WHERE id = ?::uuid",
                UUID.class,
                bens))
        .isEqualTo(ben.employee());

    // A cancelled schedule and a replaced original are refused; the successor is not.
    Instant[] t4 = instants(jdbc, s4);
    clock.set(t4[0].plus(HOUR));
    w.problem(at(ana, newId(), s4, IN, t4[0]), 404, "ATTENDANCE_SHIFT_NOT_FOUND");
    Instant[] t5 = instants(jdbc, s5);
    clock.set(t5[0].plus(HOUR));
    w.problem(at(ana, newId(), s5, IN, t5[0]), 404, "ATTENDANCE_SHIFT_NOT_FOUND");
    w.expect(at(ana, newId(), s5b, IN, t5[0]), 201);
    w.expect(at(ana, newId(), s5b, OUT, t5[0].plus(HOUR)), 201);

    // A recorded check-in survives a later cancellation; the offline check-out still applies.
    Instant[] t6 = instants(jdbc, s6);
    clock.set(t6[0].minus(HOUR));
    w.expect(checkIn(ana, Organizations.newKey(), s6), 201);
    w.expect(cancel(w.admin, Organizations.newKey(), later, "Fermeture"), 200);
    clock.set(t6[1].plus(HOUR));
    JsonNode done = w.expect(at(ana, newId(), s6, OUT, t6[1]), 201);
    assertThat(done.get("recordStatus").asText()).isEqualTo("COMPLETED");
    assertThat(events(ana.employee())).isEqualTo(4);
  }

  // ------------------------------------------------------------------------------------------
  // 12, 14: only the caller's linked employee; refusals disclose nothing
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyTheCallersLinkedEmployeeSynchronizesAndRefusalsDiscloseNothing() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    Person cy = w.person("Cy", "Mukendi");
    LocalDate day = w.today.plusDays(3);
    String schedule =
        w.published(
            proposal(
                w.site(),
                day,
                day,
                List.of(shift("a", day, 8, 16, ana.employee()), shift("u", day, 9, 12, null))));
    String mine = slotIds(w, schedule).get(0);
    String uncovered = slotIds(w, schedule).get(1);
    ScheduleWorld foreign = new ScheduleWorld(mvc, jdbc, lookups);
    Person zoe = foreign.person("Zoe", "Kanku");
    String theirs = shiftOf(foreign, zoe, 3);
    Instant[] shift = instants(jdbc, mine);
    Instant claimed = shift[0].minus(HOUR);
    clock.set(shift[0]);
    String client = newId();

    List<String> submitted = new ArrayList<>(List.of(mine, theirs, uncovered, client));
    List<JsonNode> answers = new ArrayList<>();
    for (Object slot : List.of(newId(), "not-a-uuid", theirs, uncovered)) {
      Map<String, Object> body = new HashMap<>(offline(client, mine, IN, claimed, KINSHASA));
      body.put("slotId", slot);
      answers.add(stable(w.problem(sync(ana.bearer(), body), 404, "ATTENDANCE_SHIFT_NOT_FOUND")));
    }
    // Another employee's shift: the same answer.
    answers.add(
        stable(w.problem(at(ben, client, mine, IN, claimed), 404, "ATTENDANCE_SHIFT_NOT_FOUND")));
    assertThat(answers).allMatch(answers.get(0)::equals);
    assertThat(answers.get(0).get("params").size()).isZero();

    String record =
        w.expect(at(ana, client, mine, IN, claimed), 201).get("attendanceRecordId").asText();
    submitted.add(record);
    // Check-outs: unknown, malformed, foreign, uncovered and another employee's are one 404.
    List<JsonNode> records = new ArrayList<>();
    for (Object slot : List.of(newId(), "nope", theirs, uncovered)) {
      Map<String, Object> body = new HashMap<>(offline(newId(), mine, OUT, shift[0], KINSHASA));
      body.put("slotId", slot);
      records.add(stable(w.problem(sync(ana.bearer(), body), 404, "ATTENDANCE_RECORD_NOT_FOUND")));
    }
    records.add(
        stable(
            w.problem(at(ben, newId(), mine, OUT, shift[0]), 404, "ATTENDANCE_RECORD_NOT_FOUND")));
    assertThat(records).allMatch(records.get(0)::equals);

    // Roles: a tenant administrator without a link, a platform administrator, a subject without
    // a membership (an invitation holder) and an unlinked employee gain no authority.
    Map<String, Object> body = offline(newId(), mine, OUT, shift[0], KINSHASA);
    assertThat(w.call(sync(w.admin, body)).getStatus()).isEqualTo(403);
    String platform =
        "Bearer "
            + TestTokens.token()
                .subject("sub-platform-" + UUID.randomUUID())
                .roles(List.of("platform-admin"))
                .build();
    assertThat(w.call(sync(platform, body)).getStatus()).isIn(401, 403);
    String stranger =
        "Bearer "
            + TestTokens.token()
                .tenant(w.tenant())
                .subject("sub-invited-" + UUID.randomUUID())
                .roles(List.of("employee"))
                .build();
    assertThat(w.call(sync(stranger, body)).getStatus()).isEqualTo(403);
    w.unlink(cy.employee());
    w.problem(sync(cy.bearer(), body), 403, "EMPLOYEE_LINK_REQUIRED");
    // Validation: a field the server owns is never accepted.
    for (String owned : List.of("tenantId", "employeeId", "serverReceivedAt", "recordingMethod")) {
      Map<String, Object> extra = new HashMap<>(body);
      extra.put(owned, "x");
      w.problem(sync(ben.bearer(), extra), 400, "VALIDATION_FAILED");
    }
    for (JsonNode answer : List.of(answers.get(0), records.get(0))) {
      for (String value : submitted) {
        assertThat(answer.toString()).doesNotContain(value);
      }
      assertThat(answer.toString()).doesNotContain(claimed.toString()).doesNotContain("+01:00");
    }
    assertThat(events(ana.employee())).isEqualTo(1);
    assertThat(events(ben.employee())).isZero();
    assertThat(events(cy.employee())).isZero();
    assertThat(Set.copyOf(evidence(foreign.tenant()))).isEmpty();
  }
}

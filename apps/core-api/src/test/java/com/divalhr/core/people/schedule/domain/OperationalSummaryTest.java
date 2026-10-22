package com.divalhr.core.people.schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.people.schedule.domain.OperationalStatus.Detail;
import com.divalhr.core.people.schedule.domain.OperationalStatus.NotCheckedIn;
import com.divalhr.core.people.schedule.domain.OperationalStatus.OverdueLeave;
import com.divalhr.core.people.schedule.domain.OperationalStatus.OverdueTimesheet;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Shifts;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Status;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Waiting;
import com.divalhr.core.people.schedule.domain.OperationalSummary.InvalidOutputException;
import com.divalhr.core.people.schedule.domain.OperationalSummary.InvalidSourceException;
import com.divalhr.core.people.schedule.domain.OperationalSummary.Language;
import com.divalhr.core.people.schedule.domain.Substitution.Shift;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Fact;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Highlight;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Limitation;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Request;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MVP-101 (ADR 0027): Core's candidate-fact construction and output validation against the shared
 * evaluation set ({@code packages/shared-contracts/evaluation/operational-summary/v1}) that the AI
 * service is also proven against: every case builds exactly the facts the fixture states, every
 * reviewed English and French rendering is accepted, and every kind of ungrounded answer is
 * rejected. No Spring context and no database.
 */
class OperationalSummaryTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Path EVALUATION =
      Path.of(System.getProperty("divalhr.repoRoot", "../.."))
          .resolve("packages/shared-contracts/evaluation/operational-summary/v1");

  private static JsonNode fixture(String name) throws IOException {
    return JSON.readTree(Files.readString(EVALUATION.resolve(name)));
  }

  static Stream<String> caseIds() throws IOException {
    List<String> ids = new ArrayList<>();
    for (JsonNode item : fixture("cases.json").get("cases")) {
      ids.add(item.get("id").asString());
    }
    return ids.stream();
  }

  private static JsonNode caseOf(String id) throws IOException {
    for (JsonNode item : fixture("cases.json").get("cases")) {
      if (item.get("id").asString().equals(id)) {
        return item;
      }
    }
    throw new IllegalArgumentException(id);
  }

  private static Status status(JsonNode in) {
    JsonNode s = in.get("shifts");
    JsonNode approvals = in.get("approvals");
    Integer uncovered =
        in.get("coverage").isNull() ? null : in.get("coverage").get("uncovered").asInt();
    boolean team = uncovered == null;
    return new Status(
        Instant.parse(in.get("asOfInstant").asString()),
        new Shifts(
            s.get("scheduled").asInt(),
            s.get("expected").asInt(),
            s.get("notCheckedIn").asInt(),
            s.get("present").asInt(),
            s.get("completed").asInt(),
            s.get("late").asInt()),
        uncovered,
        new Waiting(
            approvals.get("leave").get("pending").asInt(),
            approvals.get("leave").get("overdue").asInt()),
        new Waiting(
            approvals.get("timesheets").get("pending").asInt(),
            approvals.get("timesheets").get("overdue").asInt()),
        new Detail<NotCheckedIn>(List.of(), false),
        team ? null : new Detail<Shift>(List.of(), false),
        team ? new Detail<OverdueLeave>(List.of(), false) : null,
        team ? new Detail<OverdueTimesheet>(List.of(), false) : null);
  }

  private static Request snapshotOf(JsonNode item, Language language, long seed) {
    JsonNode in = item.get("input");
    return OperationalSummary.snapshot(
        status(in),
        language,
        in.get("scope").get("siteFilter").asBoolean(),
        in.get("scope").get("legalEntityFilter").asBoolean(),
        new Random(seed));
  }

  private static String signature(
      String type, int value, Integer threshold, String severity, String route) {
    return type + "|" + value + "|" + threshold + "|" + severity + "|" + route;
  }

  @ParameterizedTest
  @MethodSource("caseIds")
  void theClosedRequestHasExactlyTheFixturesFactsAndAggregates(String id) throws Exception {
    JsonNode item = caseOf(id);
    JsonNode expected = item.get("input");
    Request request = snapshotOf(item, Language.EN, 7);
    JsonNode actual = JSON.valueToTree(request.toJson());

    for (String section :
        List.of("schemaVersion", "scope", "thresholds", "shifts", "coverage", "approvals")) {
      assertThat(actual.get(section)).as(section).isEqualTo(expected.get(section));
    }
    assertThat(actual.get("language").asString()).isEqualTo("en");
    assertThat(actual.get("asOfInstant").asString())
        .isEqualTo(expected.get("asOfInstant").asString());

    Set<String> wanted = new HashSet<>();
    for (JsonNode fact : expected.get("facts")) {
      wanted.add(
          signature(
              fact.get("type").asString(),
              fact.get("value").asInt(),
              fact.get("thresholdValue").isNull() ? null : fact.get("thresholdValue").asInt(),
              fact.get("severity").asString(),
              fact.get("routeId").asString()));
    }
    Set<String> built = new HashSet<>();
    Set<String> ids = new HashSet<>();
    for (Fact fact : request.facts()) {
      built.add(
          signature(
              fact.type(), fact.value(), fact.thresholdValue(), fact.severity(), fact.routeId()));
      ids.add(fact.factId());
    }
    assertThat(built).isEqualTo(wanted);
    assertThat(request.facts()).hasSameSizeAs(wanted);
    Set<String> permutation = new HashSet<>();
    for (int i = 1; i <= request.facts().size(); i++) {
      permutation.add("f" + i);
    }
    assertThat(ids).isEqualTo(permutation);
    assertThat(OperationalSummary.expectedHeadline(request))
        .isEqualTo(item.get("expected").get("headlineCode").asString());
  }

  @ParameterizedTest
  @MethodSource("caseIds")
  void everyReviewedRenderingIsAcceptedInBothLanguages(String id) throws Exception {
    JsonNode item = caseOf(id);
    JsonNode texts = fixture("expected-text.json").get("cases").get(id);
    for (Language language : Language.values()) {
      Request request = snapshotOf(item, language, 11);
      Response response = reviewed(item, texts.get(language.wire()), request, language);
      OperationalSummary.validate(request, response);
    }
  }

  /** The fixture's rendering, re-bound to this request's own (permuted) fact IDs. */
  private static Response reviewed(
      JsonNode item, JsonNode text, Request request, Language language) {
    Map<String, String> fixtureTypes = new LinkedHashMap<>();
    for (JsonNode fact : item.get("input").get("facts")) {
      fixtureTypes.put(fact.get("factId").asString(), fact.get("type").asString());
    }
    Map<String, Fact> byType = new LinkedHashMap<>();
    request.facts().forEach(fact -> byType.put(fact.type(), fact));
    List<Highlight> highlights = new ArrayList<>();
    int index = 0;
    for (JsonNode fixtureId : item.get("expected").get("factIds")) {
      Fact fact = byType.get(fixtureTypes.get(fixtureId.asString()));
      highlights.add(
          new Highlight(
              fact.factId(),
              fact.type(),
              fact.severity(),
              fact.routeId(),
              text.get("highlights").get(index++).asString()));
    }
    List<String> codes = List.of("RECORDED_FACTS_ONLY", "NO_CONCLUSION", "REVIEW_SOURCES");
    List<Limitation> limitations = new ArrayList<>();
    for (int i = 0; i < codes.size(); i++) {
      limitations.add(new Limitation(codes.get(i), text.get("limitations").get(i).asString()));
    }
    return new Response(
        language.wire(),
        "MODEL",
        "ops-summary-1",
        item.get("expected").get("headlineCode").asString(),
        text.get("headline").asString(),
        highlights,
        limitations);
  }

  @Test
  void factIdsAreAFreshRandomPermutationPerRequest() throws Exception {
    JsonNode item = caseOf("many-critical-over-cap");
    Set<String> idsOfTheFirstFact = new HashSet<>();
    for (long seed = 0; seed < 40; seed++) {
      idsOfTheFirstFact.add(snapshotOf(item, Language.EN, seed).facts().get(0).factId());
    }
    assertThat(idsOfTheFirstFact.size()).isGreaterThan(3);
  }

  // ------------------------------------------------------------------------------------------
  // Every kind of ungrounded answer is rejected
  // ------------------------------------------------------------------------------------------

  private record Base(Request request, Response response) {}

  private static Base base() throws IOException {
    JsonNode item = caseOf("many-critical-over-cap");
    Request request = snapshotOf(item, Language.EN, 3);
    JsonNode texts =
        fixture("expected-text.json").get("cases").get("many-critical-over-cap").get("en");
    return new Base(request, reviewed(item, texts, request, Language.EN));
  }

  private static Response with(Response r, List<Highlight> highlights) {
    return new Response(
        r.language(),
        r.generationMode(),
        r.templateVersion(),
        r.headlineCode(),
        r.headlineText(),
        highlights,
        r.limitations());
  }

  private static Response withHeadline(Response r, String code, String text) {
    return new Response(
        r.language(),
        r.generationMode(),
        r.templateVersion(),
        code,
        text,
        r.highlights(),
        r.limitations());
  }

  private static Highlight text(Highlight h, String text) {
    return new Highlight(h.factId(), h.factType(), h.severity(), h.routeId(), text);
  }

  private static void rejected(Base base, Response response, String reason) {
    assertThatThrownBy(() -> OperationalSummary.validate(base.request(), response))
        .isInstanceOf(InvalidOutputException.class)
        .extracting(e -> ((InvalidOutputException) e).reason())
        .isEqualTo(reason);
  }

  @Test
  void aGroundedAnswerIsAccepted() throws Exception {
    Base b = base();
    OperationalSummary.validate(b.request(), b.response());
  }

  @Test
  void languageModeVersionAndHeadlineMustMatch() throws Exception {
    Base b = base();
    Response r = b.response();
    rejected(
        b,
        new Response(
            "fr",
            r.generationMode(),
            r.templateVersion(),
            r.headlineCode(),
            r.headlineText(),
            r.highlights(),
            r.limitations()),
        "language");
    rejected(
        b,
        new Response(
            r.language(),
            "OTHER",
            r.templateVersion(),
            r.headlineCode(),
            r.headlineText(),
            r.highlights(),
            r.limitations()),
        "mode");
    rejected(
        b,
        new Response(
            r.language(),
            r.generationMode(),
            "Bad Version!",
            r.headlineCode(),
            r.headlineText(),
            r.highlights(),
            r.limitations()),
        "version");
    rejected(b, withHeadline(r, "OPERATIONS_WITHIN_RANGE", r.headlineText()), "headline");
  }

  @Test
  void selectionMustStayInsideTheSuppliedFacts() throws Exception {
    Base b = base();
    Response r = b.response();
    List<Highlight> list = new ArrayList<>(r.highlights());
    list.set(
        0,
        new Highlight(
            "f99",
            list.get(0).factType(),
            list.get(0).severity(),
            list.get(0).routeId(),
            list.get(0).text()));
    rejected(b, with(r, list), "selection");
    List<Highlight> duplicate = new ArrayList<>(r.highlights());
    duplicate.set(1, duplicate.get(0));
    rejected(b, with(r, duplicate), "selection");
    rejected(b, with(r, List.of()), "selection");
    List<Highlight> six = new ArrayList<>(r.highlights());
    six.add(six.get(0));
    rejected(b, with(r, six), "selection");
  }

  @Test
  void aHighlightMustCarryItsOwnFactsTypeSeverityAndRoute() throws Exception {
    Base b = base();
    Response r = b.response();
    Highlight h = r.highlights().get(0);
    for (Highlight changed :
        List.of(
            new Highlight(
                h.factId(),
                "LATE_CHECK_INS".equals(h.factType()) ? "PENDING_LEAVE" : "LATE_CHECK_INS",
                h.severity(),
                h.routeId(),
                h.text()),
            new Highlight(
                h.factId(),
                h.factType(),
                "NONE".equals(h.severity()) ? "HIGH" : "NONE",
                h.routeId(),
                h.text()),
            new Highlight(
                h.factId(),
                h.factType(),
                h.severity(),
                "TIMESHEETS".equals(h.routeId()) ? "ATTENDANCE" : "TIMESHEETS",
                h.text()))) {
      List<Highlight> list = new ArrayList<>(r.highlights());
      list.set(0, changed);
      rejected(b, with(r, list), "binding");
    }
  }

  @Test
  void theMandatoryFactsAreSelectedFirst() throws Exception {
    Base b = base();
    Response r = b.response();
    // Dropping a mandatory fact (and keeping five) is rejected.
    List<Highlight> dropped = new ArrayList<>(r.highlights().subList(1, r.highlights().size()));
    rejected(b, with(r, dropped), "mandatory");
    // A lower-priority fact before a mandatory one is rejected.
    List<Highlight> reordered = new ArrayList<>(r.highlights());
    Highlight last = reordered.remove(reordered.size() - 1);
    reordered.add(0, last);
    rejected(b, with(r, reordered), "order");
  }

  @Test
  void textsStateOnlyTheirOwnNumbersAndNeverLinksOrConclusions() throws Exception {
    Base b = base();
    Response r = b.response();
    Highlight h = r.highlights().get(0);
    String[][] bad = {
      {"4 shifts have no check-in recorded.", "threshold"},
      {h.text() + " 12345678 more.", "number"},
      {h.text().replaceAll("[0-9]+", "many"), "value"},
      {h.text() + " See https://example.test/x", "text"},
      {h.text() + " <b>", "text"},
      {h.text() + " 123e4567-e89b-12d3", "text"},
      {" ", "text"},
      {h.text() + " This person was absent.", "vocabulary"},
      {h.text() + " Consider disciplinary action.", "vocabulary"},
      {"x".repeat(401), "text"},
    };
    for (String[] attempt : bad) {
      List<Highlight> list = new ArrayList<>(r.highlights());
      list.set(0, text(h, attempt[0]));
      rejected(b, with(r, list), attempt[1]);
    }
    rejected(
        b, withHeadline(r, r.headlineCode(), "Employees at fault need attention."), "vocabulary");
    rejected(b, withHeadline(r, r.headlineCode(), " "), "text");
  }

  @Test
  void limitationsAreTheThreeClosedStatementsInOrder() throws Exception {
    Base b = base();
    Response r = b.response();
    List<Limitation> swapped = new ArrayList<>(r.limitations());
    swapped.add(0, swapped.remove(2));
    for (List<Limitation> changed :
        List.of(
            swapped,
            r.limitations().subList(0, 2),
            List.of(
                r.limitations().get(0),
                r.limitations().get(1),
                new Limitation("REVIEW_SOURCES", " ")),
            List.of(
                r.limitations().get(0),
                r.limitations().get(1),
                new Limitation("REVIEW_SOURCES", "See http://x.test")))) {
      rejected(
          b,
          new Response(
              r.language(),
              r.generationMode(),
              r.templateVersion(),
              r.headlineCode(),
              r.headlineText(),
              r.highlights(),
              changed),
          "limitations");
    }
  }

  @Test
  void theProhibitedVocabularyEqualsTheSharedEvaluationFixture() throws Exception {
    JsonNode vocabulary = fixture("prohibited-vocabulary.json");
    List<String> en = new ArrayList<>();
    vocabulary.get("en").forEach(n -> en.add(n.asString()));
    List<String> fr = new ArrayList<>();
    vocabulary.get("fr").forEach(n -> fr.add(n.asString()));
    assertThat(Prohibited.english()).containsExactlyInAnyOrderElementsOf(en);
    assertThat(Prohibited.french()).containsExactlyInAnyOrderElementsOf(fr);
  }

  // ------------------------------------------------------------------------------------------
  // The source must satisfy the MVP-100 invariants before anything is sent
  // ------------------------------------------------------------------------------------------

  private static Status org(Shifts shifts, Integer uncovered, Waiting leave, Waiting sheets) {
    return new Status(
        Instant.parse("2026-10-21T10:00:00Z"),
        shifts,
        uncovered,
        leave,
        sheets,
        new Detail<NotCheckedIn>(List.of(), false),
        new Detail<Shift>(List.of(), false),
        null,
        null);
  }

  @Test
  void aBrokenSourceFailsClosed() {
    Waiting none = new Waiting(0, 0);
    Shifts ok = new Shifts(4, 1, 1, 1, 1, 1);
    OperationalSummary.requireConsistent(org(ok, 0, none, none), false);
    List<Status> broken =
        List.of(
            org(new Shifts(5, 1, 1, 1, 1, 0), 0, none, none), // partition
            org(new Shifts(4, 1, 1, 1, 1, 3), 0, none, none), // late exceeds checked in
            org(ok, 0, new Waiting(1, 2), none), // overdue exceeds pending
            org(ok, 0, none, new Waiting(0, 1)),
            org(ok, null, none, none), // an organization without coverage
            org(ok, -1, none, none),
            org(new Shifts(-1, -1, 0, 0, 0, 0), 0, none, none));
    for (Status status : broken) {
      assertThatThrownBy(() -> OperationalSummary.requireConsistent(status, false))
          .isInstanceOf(InvalidSourceException.class);
    }
    // A team with coverage is also inconsistent.
    assertThatThrownBy(() -> OperationalSummary.requireConsistent(org(ok, 0, none, none), true))
        .isInstanceOf(InvalidSourceException.class);
  }

  @Test
  void languageIsExactlyEnOrFr() {
    assertThat(Language.parse("en")).contains(Language.EN);
    assertThat(Language.parse("fr")).contains(Language.FR);
    for (Object bad :
        new Object[] {
          "EN", "fr-CA", " en", "en ", "", "english", 1, true, List.of("en"), Map.of()
        }) {
      assertThat(Language.parse(bad)).isEmpty();
    }
    assertThat(Language.parse(null)).isEmpty();
  }

  @Test
  void severityBandsAreClosed() {
    assertThat(Stream.of(0, 1, 2, 3, 9, 10, 500).map(OperationalSummary::band).toList())
        .containsExactly("NONE", "LOW", "LOW", "MEDIUM", "MEDIUM", "HIGH", "HIGH");
  }
}

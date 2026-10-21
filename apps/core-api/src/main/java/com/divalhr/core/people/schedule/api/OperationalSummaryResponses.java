package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.schedule.api.OperationalStatusResponses.Approvals;
import com.divalhr.core.people.schedule.api.OperationalStatusResponses.Coverage;
import com.divalhr.core.people.schedule.api.OperationalStatusResponses.Shifts;
import com.divalhr.core.people.schedule.api.OperationalStatusResponses.Thresholds;
import com.divalhr.core.people.schedule.api.OperationalStatusResponses.Waiting;
import com.divalhr.core.people.schedule.application.OperationalSummaryService.Briefing;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Request;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Response body of the grounded daily briefing (MVP-101, ADR 0027). Nothing here is a name, an
 * identifier, a URL, a prompt, a provider or model name, a token or a reasoning trace: texts come
 * from reviewed templates, and every highlight carries the allow-listed route identifier of an
 * already-authorized source view, which the web application maps to a route.
 */
public final class OperationalSummaryResponses {

  private OperationalSummaryResponses() {}

  /**
   * The headline.
   *
   * @param code closed headline family
   * @param text rendered text in the requested language
   */
  @Schema(name = "OperationalSummaryHeadline")
  public record Headline(String code, String text) {}

  /**
   * One highlight.
   *
   * @param factType closed fact type
   * @param severity closed severity band
   * @param routeId allow-listed source-route identifier
   * @param text rendered text in the requested language
   */
  @Schema(name = "OperationalSummaryHighlight")
  public record Highlight(String factType, String severity, String routeId, String text) {}

  /**
   * One limitation statement.
   *
   * @param code closed code
   * @param text rendered text in the requested language
   */
  @Schema(name = "OperationalSummaryLimitation")
  public record Limitation(String code, String text) {}

  /**
   * The aggregate counts and fixed thresholds the briefing was generated from; the same figures the
   * operational status shows.
   *
   * @param thresholds the fixed rules
   * @param shifts shift-occurrence counts
   * @param coverage uncovered slots, or {@code null} for a manager
   * @param approvals approvals waiting
   */
  @Schema(name = "OperationalSummarySourceSnapshot")
  public record SourceSnapshot(
      Thresholds thresholds, Shifts shifts, Coverage coverage, Approvals approvals) {}

  /**
   * The briefing.
   *
   * @param asOfInstant the server instant the counts describe
   * @param generatedAt when the briefing was produced
   * @param language {@code en} or {@code fr}
   * @param generationMode {@code MODEL} or {@code FALLBACK}
   * @param templateVersion the reviewed template set
   * @param headline the headline
   * @param highlights one to five highlights
   * @param sourceSnapshot the counts and thresholds
   * @param limitations the three limitation statements
   */
  @Schema(name = "OperationalSummary")
  public record Summary(
      Instant asOfInstant,
      Instant generatedAt,
      String language,
      String generationMode,
      String templateVersion,
      Headline headline,
      List<Highlight> highlights,
      SourceSnapshot sourceSnapshot,
      List<Limitation> limitations) {

    /** Copies the lists. */
    public Summary {
      highlights = List.copyOf(highlights);
      limitations = List.copyOf(limitations);
    }

    static Summary of(Briefing briefing) {
      Request request = briefing.request();
      var response = briefing.response();
      var c = request.shifts();
      return new Summary(
          Instant.parse(request.asOfInstant()),
          briefing.generatedAt(),
          response.language(),
          response.generationMode(),
          response.templateVersion(),
          new Headline(response.headlineCode(), response.headlineText()),
          response.highlights().stream()
              .map(h -> new Highlight(h.factType(), h.severity(), h.routeId(), h.text()))
              .toList(),
          new SourceSnapshot(
              Thresholds.fixed(),
              new Shifts(
                  c.scheduled(),
                  c.expected(),
                  c.notCheckedIn(),
                  c.present(),
                  c.completed(),
                  c.late()),
              request.uncovered() == null ? null : new Coverage(request.uncovered()),
              new Approvals(
                  new Waiting(request.leave().pending(), request.leave().overdue()),
                  new Waiting(request.timesheets().pending(), request.timesheets().overdue()))),
          response.limitations().stream().map(l -> new Limitation(l.code(), l.text())).toList());
    }
  }
}

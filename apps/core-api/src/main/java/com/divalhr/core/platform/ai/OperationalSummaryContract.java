package com.divalhr.core.platform.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The closed Core to AI service contract of the operational summary (MVP-101, ADR 0027; JSON
 * Schemas {@code operational-summary-input} and {@code operational-summary-output} in {@code
 * packages/shared-contracts}). Everything that may cross the model boundary is a field of {@link
 * Request}: aggregate counts, fixed thresholds, closed enumerations, opaque per-request fact IDs
 * and the as-of instant. There is no field that could carry a name, an identifier, a label, a date
 * other than the as-of instant, a URL, a correlation ID or any user-entered text.
 */
public final class OperationalSummaryContract {

  /** The contract version. */
  public static final int SCHEMA_VERSION = 1;

  private OperationalSummaryContract() {}

  /**
   * Shift-occurrence counts.
   *
   * @param scheduled all
   * @param expected awaiting a check-in within the grace
   * @param notCheckedIn no check-in after the grace
   * @param present in progress
   * @param completed finished
   * @param late late subset
   */
  public record Shifts(
      int scheduled, int expected, int notCheckedIn, int present, int completed, int late) {}

  /**
   * Waiting approvals.
   *
   * @param pending all waiting
   * @param overdue waiting beyond the threshold (a subset)
   */
  public record Waiting(int pending, int overdue) {}

  /**
   * One candidate fact.
   *
   * @param factId opaque per-request identifier ({@code f1}..{@code f11})
   * @param type closed fact type
   * @param value integer value
   * @param thresholdValue 5, 30, 48, 72 or {@code null}
   * @param severity closed severity band
   * @param routeId allow-listed source-route identifier
   */
  public record Fact(
      String factId,
      String type,
      int value,
      Integer thresholdValue,
      String severity,
      String routeId) {}

  /**
   * The whole request body (the only data sent to the AI service).
   *
   * @param language {@code en} or {@code fr}
   * @param asOfInstant the pinned server instant (UTC, at most microseconds)
   * @param scopeKind {@code ORGANIZATION} or {@code TEAM}
   * @param siteFilter whether a site filter applies
   * @param legalEntityFilter whether a legal-entity filter applies
   * @param shifts shift counts
   * @param uncovered uncovered slots, or {@code null} for a team
   * @param leave leave approvals
   * @param timesheets timesheet approvals
   * @param facts the candidate facts, in the canonical type order
   */
  public record Request(
      String language,
      String asOfInstant,
      String scopeKind,
      boolean siteFilter,
      boolean legalEntityFilter,
      Shifts shifts,
      Integer uncovered,
      Waiting leave,
      Waiting timesheets,
      List<Fact> facts) {

    /** Copies the facts. */
    public Request {
      facts = List.copyOf(facts);
    }

    /**
     * The JSON object the AI service accepts.
     *
     * @return an ordered map of the closed contract
     */
    public Map<String, Object> toJson() {
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("schemaVersion", SCHEMA_VERSION);
      root.put("language", language);
      root.put("asOfInstant", asOfInstant);
      Map<String, Object> scope = new LinkedHashMap<>();
      scope.put("kind", scopeKind);
      scope.put("siteFilter", siteFilter);
      scope.put("legalEntityFilter", legalEntityFilter);
      root.put("scope", scope);
      Map<String, Object> thresholds = new LinkedHashMap<>();
      thresholds.put("lateMinutes", 5);
      thresholds.put("notCheckedInGraceMinutes", 30);
      thresholds.put("leaveOverdueHours", 48);
      thresholds.put("timesheetOverdueHours", 72);
      root.put("thresholds", thresholds);
      Map<String, Object> counts = new LinkedHashMap<>();
      counts.put("scheduled", shifts.scheduled());
      counts.put("expected", shifts.expected());
      counts.put("notCheckedIn", shifts.notCheckedIn());
      counts.put("present", shifts.present());
      counts.put("completed", shifts.completed());
      counts.put("late", shifts.late());
      root.put("shifts", counts);
      if (uncovered == null) {
        root.put("coverage", null);
      } else {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("uncovered", uncovered);
        root.put("coverage", coverage);
      }
      Map<String, Object> approvals = new LinkedHashMap<>();
      approvals.put("leave", waiting(leave));
      approvals.put("timesheets", waiting(timesheets));
      root.put("approvals", approvals);
      List<Object> list = new ArrayList<>();
      for (Fact fact : facts) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("factId", fact.factId());
        entry.put("type", fact.type());
        entry.put("value", fact.value());
        entry.put("thresholdValue", fact.thresholdValue());
        entry.put("severity", fact.severity());
        entry.put("routeId", fact.routeId());
        list.add(entry);
      }
      root.put("facts", list);
      return root;
    }

    private static Map<String, Object> waiting(Waiting waiting) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("pending", waiting.pending());
      map.put("overdue", waiting.overdue());
      return map;
    }
  }

  /**
   * A rendered highlight bound to the fact it selects.
   *
   * @param factId the selected fact
   * @param factType its type
   * @param severity its band
   * @param routeId its source route
   * @param text rendered text
   */
  public record Highlight(
      String factId, String factType, String severity, String routeId, String text) {}

  /**
   * A closed limitation statement.
   *
   * @param code closed code
   * @param text rendered text
   */
  public record Limitation(String code, String text) {}

  /**
   * The AI service's answer, already checked for shape (not yet for grounding).
   *
   * @param language language of the texts
   * @param generationMode {@code MODEL} or {@code FALLBACK}
   * @param templateVersion the reviewed template set
   * @param headlineCode closed headline code
   * @param headlineText rendered headline
   * @param highlights one to five highlights
   * @param limitations the three limitations
   */
  public record Response(
      String language,
      String generationMode,
      String templateVersion,
      String headlineCode,
      String headlineText,
      List<Highlight> highlights,
      List<Limitation> limitations) {

    /** Copies the lists. */
    public Response {
      highlights = List.copyOf(highlights);
      limitations = List.copyOf(limitations);
    }
  }
}

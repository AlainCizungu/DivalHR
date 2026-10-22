package com.divalhr.core.people.schedule;

import com.divalhr.core.people.schedule.domain.OperationalSummary.FactType;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Fact;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Highlight;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Limitation;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Request;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Response;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A stand-in for the AI service in Core tests: the deterministic priority policy of D101-9 and
 * neutral, grounded texts. It proves Core's own checks; the real renderer and its reviewed
 * bilingual templates are proven in {@code apps/ai-service}.
 */
public final class OperationalSummaryStub {

  private OperationalSummaryStub() {}

  private static int severityRank(String severity) {
    return switch (severity) {
      case "HIGH" -> 3;
      case "MEDIUM" -> 2;
      case "LOW" -> 1;
      default -> 0;
    };
  }

  /**
   * The deterministic briefing of a request.
   *
   * @param request the closed snapshot
   * @param mode {@code MODEL} or {@code FALLBACK}
   * @return a response Core must accept
   */
  public static Response answer(Request request, String mode) {
    List<Fact> ranked = new ArrayList<>(request.facts());
    ranked.sort(
        Comparator.comparingInt((Fact f) -> FactType.valueOf(f.type()).priority())
            .thenComparing(f -> -severityRank(f.severity()))
            .thenComparingInt(f -> FactType.valueOf(f.type()).ordinal()));
    List<Highlight> highlights = new ArrayList<>();
    for (Fact fact : ranked.subList(0, Math.min(5, ranked.size()))) {
      String label = fact.type().toLowerCase(Locale.ROOT).replace('_', ' ');
      String text =
          fact.value()
              + " "
              + label
              + (fact.thresholdValue() == null ? "" : " (limit " + fact.thresholdValue() + ")");
      highlights.add(
          new Highlight(fact.factId(), fact.type(), fact.severity(), fact.routeId(), text));
    }
    return new Response(
        request.language(),
        mode,
        "ops-summary-test",
        com.divalhr.core.people.schedule.domain.OperationalSummary.expectedHeadline(request),
        "Briefing of the day.",
        highlights,
        List.of(
            new Limitation("RECORDED_FACTS_ONLY", "Recorded facts only."),
            new Limitation("NO_CONCLUSION", "No conclusion."),
            new Limitation("REVIEW_SOURCES", "Review the sources.")));
  }
}

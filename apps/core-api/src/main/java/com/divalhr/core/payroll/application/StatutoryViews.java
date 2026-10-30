package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CompensationResponses.MoneyView;
import com.divalhr.core.payroll.api.StatutoryResponses.ActivationEntry;
import com.divalhr.core.payroll.api.StatutoryResponses.CalculationSummary;
import com.divalhr.core.payroll.api.StatutoryResponses.PackSummary;
import com.divalhr.core.payroll.api.StatutoryResponses.ProfileEntry;
import com.divalhr.core.payroll.api.StatutoryResponses.RuleBracket;
import com.divalhr.core.payroll.api.StatutoryResponses.RuleComponent;
import com.divalhr.core.payroll.api.StatutoryResponses.RuleMethod;
import com.divalhr.core.payroll.api.StatutoryResponses.SourceView;
import com.divalhr.core.payroll.api.StatutoryResponses.Totals;
import com.divalhr.core.payroll.api.StatutoryResponses.Trace;
import com.divalhr.core.payroll.api.StatutoryResponses.TraceSlice;
import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.StatutoryLine;
import com.divalhr.core.payroll.domain.StatutoryProfile;
import com.divalhr.core.payroll.domain.StatutoryRules;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.ActivationRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.CalculationRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.ProfileRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository.PackRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository.SourceRow;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maps statutory domain values and stored rows to the wire views (MVP-094B). The trace of a
 * progressive component is stored as JSON text with its integers as decimal strings, exactly as it
 * is returned.
 */
final class StatutoryViews {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private StatutoryViews() {}

  static MoneyView cdf(long minor) {
    return PayrollViews.money(CompensationCurrency.CDF, minor);
  }

  static Totals totals(long gross, long deductions, long contributions, long net, long cost) {
    return new Totals(cdf(gross), cdf(deductions), cdf(contributions), cdf(net), cdf(cost));
  }

  static String month(LocalDate firstDay) {
    return YearMonth.from(firstDay).toString();
  }

  static PackSummary summary(PackRow pack) {
    return new PackSummary(
        pack.id(),
        pack.code(),
        pack.version(),
        pack.country(),
        pack.currency(),
        pack.status(),
        pack.effectiveFrom(),
        pack.effectiveTo(),
        pack.methodVersion(),
        pack.rulesDigest(),
        pack.sourceCount());
  }

  static SourceView source(SourceRow source) {
    return new SourceView(
        source.key(), source.title(), source.url(), source.sha256(), source.retrievedOn());
  }

  static ActivationEntry entry(ActivationRow activation) {
    return new ActivationEntry(
        activation.id(),
        activation.sequence(),
        month(activation.fromMonth()),
        activation.packId(),
        activation.packCode(),
        activation.packVersion(),
        activation.activatedAt());
  }

  static ProfileEntry entry(ProfileRow row) {
    return entry(row.profile(), row.createdAt());
  }

  static ProfileEntry entry(StatutoryProfile profile, Instant createdAt) {
    return new ProfileEntry(
        profile.id(),
        profile.sequence(),
        profile.effectiveFrom(),
        "CD",
        profile.classification().name(),
        profile.cnssSubject(),
        profile.irppSubject(),
        profile.evidence().name(),
        createdAt);
  }

  static CalculationSummary summary(CalculationRow row) {
    return new CalculationSummary(
        row.id(),
        row.runId(),
        row.runVersion(),
        row.packId(),
        row.packCode(),
        row.packVersion(),
        month(row.periodStart()),
        row.currency(),
        row.version(),
        row.status(),
        row.supportedCount(),
        row.excludedCount(),
        totals(
            row.grossTotal(),
            row.deductionsTotal(),
            row.contributionsTotal(),
            row.netTotal(),
            row.costTotal()),
        row.createdAt(),
        row.supersededAt(),
        row.supersededById());
  }

  // ------------------------------------------------------------------------------------------
  // Rules (shown for transparency, exactly as the engine reads them)
  // ------------------------------------------------------------------------------------------

  static List<RuleComponent> components(StatutoryRules rules) {
    List<RuleComponent> views = new ArrayList<>();
    if (rules == null) {
      return views;
    }
    for (StatutoryRules.Component component : rules.components()) {
      views.add(
          new RuleComponent(
              component.id(),
              component.party().name(),
              component.category().name(),
              component.order(),
              component.requires().name(),
              component.base().name(),
              component.sourceKey(),
              component.rounding().name(),
              method(component.method())));
    }
    return views;
  }

  private static RuleMethod method(StatutoryRules.Method method) {
    if (method instanceof StatutoryRules.Rate rate) {
      return new RuleMethod("RATE", rate.rateBp(), null, null, null);
    }
    StatutoryRules.Progressive progressive = (StatutoryRules.Progressive) method;
    List<RuleBracket> brackets = new ArrayList<>();
    for (StatutoryRules.Bracket bracket : progressive.brackets()) {
      brackets.add(
          new RuleBracket(
              bracket.upToAnnual() == null ? null : Long.toString(bracket.upToAnnual()),
              bracket.rateBp()));
    }
    return new RuleMethod(
        "PROGRESSIVE", null, progressive.periodsPerYear(), progressive.capBp(), brackets);
  }

  // ------------------------------------------------------------------------------------------
  // Traces
  // ------------------------------------------------------------------------------------------

  /** The wire trace of a computed progressive component, or an empty trace. */
  static Trace trace(StatutoryLine.Trace trace) {
    if (trace == null) {
      return new Trace(null, null, null, null);
    }
    List<TraceSlice> slices = new ArrayList<>();
    for (StatutoryLine.Slice slice : trace.slices()) {
      slices.add(
          new TraceSlice(
              Long.toString(slice.from()),
              slice.amount().add(BigInteger.valueOf(slice.from())).toString(),
              slice.rateBp(),
              slice.amount().toString()));
    }
    return new Trace(
        trace.annualBase().toString(), slices, trace.numerator().toString(), trace.capApplied());
  }

  /** The JSON text stored for a computed progressive component, or {@code null}. */
  static String traceJson(StatutoryLine.Trace trace) {
    if (trace == null) {
      return null;
    }
    Trace wire = trace(trace);
    StringBuilder json = new StringBuilder("{\"annualBase\":\"");
    json.append(wire.annualBase()).append("\",\"slices\":[");
    for (int i = 0; i < wire.slices().size(); i++) {
      TraceSlice slice = wire.slices().get(i);
      if (i > 0) {
        json.append(',');
      }
      json.append("{\"from\":\"")
          .append(slice.from())
          .append("\",\"to\":\"")
          .append(slice.to())
          .append("\",\"rateBp\":")
          .append(slice.rateBp())
          .append(",\"amount\":\"")
          .append(slice.amount())
          .append("\"}");
    }
    json.append("],\"numerator\":\"")
        .append(wire.numerator())
        .append("\",\"capApplied\":")
        .append(wire.capApplied())
        .append('}');
    return json.toString();
  }

  /** The wire trace of a stored component. */
  static Trace trace(String json) {
    if (json == null) {
      return new Trace(null, null, null, null);
    }
    JsonNode node = JSON.readTree(json);
    List<TraceSlice> slices = new ArrayList<>();
    for (JsonNode slice : node.path("slices")) {
      slices.add(
          new TraceSlice(
              slice.path("from").asString(null),
              slice.path("to").asString(null),
              slice.path("rateBp").asInt(0),
              slice.path("amount").asString(null)));
    }
    return new Trace(
        node.path("annualBase").asString(null),
        slices,
        node.path("numerator").asString(null),
        node.path("capApplied").asBoolean(false));
  }
}

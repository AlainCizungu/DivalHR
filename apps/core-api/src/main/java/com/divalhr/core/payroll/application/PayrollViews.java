package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CompensationResponses.MoneyView;
import com.divalhr.core.payroll.api.PayrollResponses.PayDayView;
import com.divalhr.core.payroll.api.PayrollResponses.PolicyAppliedView;
import com.divalhr.core.payroll.api.PayrollResponses.PolicyEntryView;
import com.divalhr.core.payroll.api.PayrollResponses.RunSummary;
import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.PayrollMoney;
import com.divalhr.core.payroll.domain.PayrollPayDay;
import com.divalhr.core.payroll.domain.PayrollPeriod;
import com.divalhr.core.payroll.domain.PayrollPolicy;
import com.divalhr.core.payroll.domain.PayrollPolicyTimeline;
import com.divalhr.core.payroll.domain.PayrollPolicyTimeline.Entry;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository.RunRow;
import java.time.YearMonth;
import java.util.List;

/** Maps payroll domain values and stored rows to the wire views (MVP-094A). */
final class PayrollViews {

  private PayrollViews() {}

  static PayDayView payDay(PayrollPayDay payDay) {
    return new PayDayView(payDay.kind().name(), payDay.day());
  }

  static MoneyView money(CompensationCurrency currency, long minor) {
    return new MoneyView(currency.name(), PayrollMoney.text(currency, minor));
  }

  static List<PolicyEntryView> entries(List<PayrollPolicy> policies) {
    return PayrollPolicyTimeline.entries(policies).stream().map(PayrollViews::entry).toList();
  }

  static PolicyEntryView entry(Entry entry) {
    PayrollPolicy policy = entry.policy();
    return new PolicyEntryView(
        policy.id(),
        policy.sequence(),
        policy.effectiveFrom(),
        entry.effectiveTo(),
        payDay(policy.payDay()),
        policy.method().name());
  }

  static PolicyAppliedView applied(PayrollPolicy policy) {
    return new PolicyAppliedView(
        policy.id(), policy.sequence(), payDay(policy.payDay()), policy.method().name());
  }

  static RunSummary summary(RunRow run) {
    return new RunSummary(
        run.id(),
        run.legalEntityId(),
        new PayrollPeriod(YearMonth.from(run.periodStart())).text(),
        run.currency().name(),
        run.version(),
        run.status(),
        run.policySequence(),
        run.employeeCount(),
        money(run.currency(), run.totalMinor()),
        run.createdAt(),
        run.supersededAt(),
        run.supersededByRunId());
  }
}

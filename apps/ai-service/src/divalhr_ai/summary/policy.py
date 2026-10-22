"""Canonical facts, semantic snapshot validation, the deterministic priority policy and plan checks.

One implementation of the rules that Core also applies when it builds the candidate facts, so a
snapshot that Core could not have produced is rejected here before any planner or model runs
(D101-3: an invalid snapshot fails closed) and a plan is accepted only if it stays inside the
supplied facts and the mandatory-fact rule (D101-9).
"""

from dataclasses import dataclass

from divalhr_ai.summary.contract import (
    MAX_SELECTED,
    Fact,
    FactType,
    HeadlineCode,
    Plan,
    RouteId,
    ScopeKind,
    Severity,
    SummaryInput,
)

T = FactType

#: The stable fact-type order (the last tie-breaker of the policy and the canonical fact order).
TYPE_ORDER: tuple[FactType, ...] = (
    T.NOT_CHECKED_IN,
    T.UNCOVERED_SHIFTS,
    T.OVERDUE_LEAVE,
    T.OVERDUE_TIMESHEETS,
    T.LATE_CHECK_INS,
    T.PENDING_LEAVE,
    T.PENDING_TIMESHEETS,
    T.EXPECTED_SHIFTS,
    T.PRESENT_SHIFTS,
    T.COMPLETED_SHIFTS,
    T.SCHEDULED_SHIFTS,
)

#: D101-9 priorities: 1 not checked in, 2 uncovered, 3 overdue approvals, 4 late, 5 pending
#: approvals, 6 expected, 7 normal status.
PRIORITY: dict[FactType, int] = {
    T.NOT_CHECKED_IN: 1,
    T.UNCOVERED_SHIFTS: 2,
    T.OVERDUE_LEAVE: 3,
    T.OVERDUE_TIMESHEETS: 3,
    T.LATE_CHECK_INS: 4,
    T.PENDING_LEAVE: 5,
    T.PENDING_TIMESHEETS: 5,
    T.EXPECTED_SHIFTS: 6,
    T.PRESENT_SHIFTS: 7,
    T.COMPLETED_SHIFTS: 7,
    T.SCHEDULED_SHIFTS: 7,
}
MANDATORY_MAX_PRIORITY = 3

THRESHOLD: dict[FactType, int | None] = {
    T.NOT_CHECKED_IN: 30,
    T.UNCOVERED_SHIFTS: None,
    T.OVERDUE_LEAVE: 48,
    T.OVERDUE_TIMESHEETS: 72,
    T.LATE_CHECK_INS: 5,
    T.PENDING_LEAVE: 48,
    T.PENDING_TIMESHEETS: 72,
    T.EXPECTED_SHIFTS: 30,
    T.PRESENT_SHIFTS: None,
    T.COMPLETED_SHIFTS: None,
    T.SCHEDULED_SHIFTS: None,
}
ROUTE: dict[FactType, RouteId] = {
    T.NOT_CHECKED_IN: RouteId.ATTENDANCE,
    T.UNCOVERED_SHIFTS: RouteId.SCHEDULES,
    T.OVERDUE_LEAVE: RouteId.LEAVE_APPROVALS,
    T.OVERDUE_TIMESHEETS: RouteId.TIMESHEETS,
    T.LATE_CHECK_INS: RouteId.ATTENDANCE,
    T.PENDING_LEAVE: RouteId.LEAVE_APPROVALS,
    T.PENDING_TIMESHEETS: RouteId.TIMESHEETS,
    T.EXPECTED_SHIFTS: RouteId.ATTENDANCE,
    T.PRESENT_SHIFTS: RouteId.ATTENDANCE,
    T.COMPLETED_SHIFTS: RouteId.ATTENDANCE,
    T.SCHEDULED_SHIFTS: RouteId.SCHEDULES,
}
#: Status facts carry no severity: the policy orders them by the stable type order.
UNBANDED: frozenset[FactType] = frozenset(
    {T.PRESENT_SHIFTS, T.COMPLETED_SHIFTS, T.SCHEDULED_SHIFTS}
)
_RANK = {Severity.NONE: 0, Severity.LOW: 1, Severity.MEDIUM: 2, Severity.HIGH: 3}


class SnapshotInvalidError(ValueError):
    """The snapshot breaks an MVP-100 invariant; never carries the offending value."""


class PlanInvalidError(ValueError):
    """A plan stays outside the supplied facts or the policy; never carries the plan."""


def band(value: int) -> Severity:
    """The bounded severity band of a count: 0, 1 to 2, 3 to 9, 10 or more."""
    if value == 0:
        return Severity.NONE
    if value <= 2:
        return Severity.LOW
    if value <= 9:
        return Severity.MEDIUM
    return Severity.HIGH


def _values(snapshot: SummaryInput) -> dict[FactType, int]:
    shifts = snapshot.shifts
    leave = snapshot.approvals.leave
    sheets = snapshot.approvals.timesheets
    values = {
        T.NOT_CHECKED_IN: shifts.not_checked_in,
        T.OVERDUE_LEAVE: leave.overdue,
        T.OVERDUE_TIMESHEETS: sheets.overdue,
        T.LATE_CHECK_INS: shifts.late,
        T.PENDING_LEAVE: leave.pending - leave.overdue,
        T.PENDING_TIMESHEETS: sheets.pending - sheets.overdue,
        T.EXPECTED_SHIFTS: shifts.expected,
        T.PRESENT_SHIFTS: shifts.present,
        T.COMPLETED_SHIFTS: shifts.completed,
        T.SCHEDULED_SHIFTS: shifts.scheduled,
    }
    if snapshot.coverage is not None:
        values[T.UNCOVERED_SHIFTS] = snapshot.coverage.uncovered
    return values


def expected_facts(
    snapshot: SummaryInput,
) -> dict[FactType, tuple[int, int | None, Severity, RouteId]]:
    """The facts Core must have produced from the aggregates, keyed by type."""
    expected = {}
    for fact_type, value in _values(snapshot).items():
        if value == 0 and fact_type is not T.SCHEDULED_SHIFTS:
            continue
        severity = Severity.NONE if fact_type in UNBANDED else band(value)
        expected[fact_type] = (value, THRESHOLD[fact_type], severity, ROUTE[fact_type])
    return expected


def validate_snapshot(snapshot: SummaryInput) -> None:
    """Fail closed unless the MVP-100 invariants hold and the facts equal the aggregates."""
    shifts = snapshot.shifts
    leave = snapshot.approvals.leave
    sheets = snapshot.approvals.timesheets
    if (
        shifts.expected + shifts.not_checked_in + shifts.present + shifts.completed
        != shifts.scheduled
    ):
        raise SnapshotInvalidError("partition")
    if shifts.late > shifts.present + shifts.completed:
        raise SnapshotInvalidError("late")
    if leave.overdue > leave.pending or sheets.overdue > sheets.pending:
        raise SnapshotInvalidError("overdue")
    if (snapshot.scope.kind is ScopeKind.TEAM) != (snapshot.coverage is None):
        raise SnapshotInvalidError("coverage")
    if snapshot.scope.kind is ScopeKind.TEAM and (
        snapshot.scope.site_filter or snapshot.scope.legal_entity_filter
    ):
        raise SnapshotInvalidError("filters")
    ids = [fact.fact_id for fact in snapshot.facts]
    if len(set(ids)) != len(ids):
        raise SnapshotInvalidError("ids")
    expected = expected_facts(snapshot)
    actual = {fact.type: fact for fact in snapshot.facts}
    if len(actual) != len(snapshot.facts) or set(actual) != set(expected):
        raise SnapshotInvalidError("facts")
    for fact_type, (value, threshold, severity, route) in expected.items():
        fact = actual[fact_type]
        if (fact.value, fact.threshold_value, fact.severity, fact.route_id) != (
            value,
            threshold,
            severity,
            route,
        ):
            raise SnapshotInvalidError("fact")


def _sort_key(fact: Fact) -> tuple[int, int, int]:
    return (PRIORITY[fact.type], -_RANK[fact.severity], TYPE_ORDER.index(fact.type))


def ranked(snapshot: SummaryInput) -> list[Fact]:
    """Every fact in policy order: priority, larger severity band, stable type order."""
    return sorted(snapshot.facts, key=_sort_key)


def mandatory_ids(snapshot: SummaryInput) -> set[str]:
    """Every non-zero fact of priorities 1 to 3: always selected (D101-9)."""
    return {
        fact.fact_id
        for fact in snapshot.facts
        if PRIORITY[fact.type] <= MANDATORY_MAX_PRIORITY and fact.value > 0
    }


def expected_headline(snapshot: SummaryInput) -> HeadlineCode:
    """The one headline family the facts support."""
    values = {fact.type: fact.value for fact in snapshot.facts}
    attendance = values.get(T.NOT_CHECKED_IN, 0) > 0 or values.get(T.LATE_CHECK_INS, 0) > 0
    coverage = values.get(T.UNCOVERED_SHIFTS, 0) > 0
    approvals = values.get(T.OVERDUE_LEAVE, 0) > 0 or values.get(T.OVERDUE_TIMESHEETS, 0) > 0
    areas = int(attendance) + int(coverage) + int(approvals)
    if areas == 0:
        return HeadlineCode.OPERATIONS_WITHIN_RANGE
    if areas > 1:
        return HeadlineCode.SEVERAL_AREAS_NEED_ATTENTION
    if coverage:
        return HeadlineCode.COVERAGE_NEEDS_ATTENTION
    if approvals:
        return HeadlineCode.APPROVALS_NEED_ATTENTION
    return HeadlineCode.ATTENDANCE_NEEDS_ATTENTION


def deterministic_plan(snapshot: SummaryInput) -> Plan:
    """The oracle and fallback: the first five facts in policy order."""
    selected = tuple(fact.fact_id for fact in ranked(snapshot)[:MAX_SELECTED])
    return Plan(headlineCode=expected_headline(snapshot), factIds=selected)


@dataclass(frozen=True)
class CheckedPlan:
    """A plan proven to stay inside the facts and the policy, with its facts in order."""

    headline: HeadlineCode
    facts: tuple[Fact, ...]


def check_plan(snapshot: SummaryInput, plan: Plan) -> CheckedPlan:
    """Reject a plan that selects an unknown or repeated fact, suppresses a mandatory fact, puts a
    lower-priority fact before a mandatory one, or names a headline the facts do not support."""
    by_id = {fact.fact_id: fact for fact in snapshot.facts}
    if len(set(plan.fact_ids)) != len(plan.fact_ids) or len(plan.fact_ids) > MAX_SELECTED:
        raise PlanInvalidError("selection")
    if any(fact_id not in by_id for fact_id in plan.fact_ids):
        raise PlanInvalidError("unknown")
    selected = tuple(by_id[fact_id] for fact_id in plan.fact_ids)
    if not mandatory_ids(snapshot) <= set(plan.fact_ids):
        raise PlanInvalidError("mandatory")
    seen_other = False
    for fact in selected:
        mandatory = fact.fact_id in mandatory_ids(snapshot)
        if mandatory and seen_other:
            raise PlanInvalidError("order")
        seen_other = seen_other or not mandatory
    if plan.headline_code is not expected_headline(snapshot):
        raise PlanInvalidError("headline")
    return CheckedPlan(headline=plan.headline_code, facts=selected)

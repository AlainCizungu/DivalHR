#!/usr/bin/env python3
"""LAUNCH-002 / R231-3: the machine-readable result of the production browser journeys.

  verify-journeys.py <playwright json report>

Passes only when the report holds EXACTLY the two expected journeys and each ran once and passed:
no failed, skipped, flaky, interrupted or not-run test, no retry, no extra or missing test. Prints a
short, redacted summary (titles and statuses only). A missing or unreadable report fails.
"""
import json
import sys

EXPECTED = 2


def tests(suite):
    for spec in suite.get("specs", []):
        for test in spec.get("tests", []):
            yield spec.get("title", "?"), test
    for child in suite.get("suites", []):
        yield from tests(child)


def main(path):
    try:
        report = json.load(open(path, encoding="utf-8"))
    except (OSError, ValueError) as error:
        print(f"FAIL no readable result report: {error}")
        return 1
    found = [item for suite in report.get("suites", []) for item in tests(suite)]
    stats = report.get("stats", {})
    problems = []
    if len(found) != EXPECTED:
        problems.append(f"{len(found)} tests found, exactly {EXPECTED} are required")
    for title, test in found:
        results = test.get("results", [])
        status = test.get("status")
        if status != "expected" or len(results) != 1 or results[0].get("status") != "passed" \
                or results[0].get("retry", 0) != 0:
            problems.append(f"{title}: status={status} results={[r.get('status') for r in results]}")
        print(f"{'PASS' if status == 'expected' else 'FAIL'} {title} ({status})")
    for key in ("unexpected", "skipped", "flaky"):
        if stats.get(key, 0):
            problems.append(f"{key}={stats.get(key)}")
    if stats.get("expected") != EXPECTED:
        problems.append(f"expected={stats.get('expected')}, required {EXPECTED}")
    if report.get("errors"):
        problems.append(f"{len(report['errors'])} global errors")
    if problems:
        print("FAIL " + "; ".join(problems))
        return 1
    print(f"PASS exactly {EXPECTED} journeys, none failed, skipped, flaky or retried")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]) if len(sys.argv) == 2 else 2)

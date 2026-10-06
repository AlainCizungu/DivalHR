#!/usr/bin/env python3
"""OPS-001 (A65-7): evaluates a commit's GitHub check runs for a test-environment deployment.

    provenance.py <required-checks file> <sha>  < check-runs JSON (GitHub REST, one or more pages
                                                   concatenated as a JSON array of responses or a
                                                   single response object)

Fails closed. PASS requires that:
  * the response describes this exact SHA (every run's head_sha equals it) and is not truncated;
  * every required name or pattern matches at least one run, and its latest run per name succeeded;
  * every run present, required or not, is completed with success, neutral or skipped.
Prints one line per decision; exit status 0 only for PASS.
"""

import fnmatch
import json
import sys

OK = {"success", "neutral", "skipped"}


def load_runs(raw: str):
    data = json.loads(raw)
    pages = data if isinstance(data, list) else [data]
    runs, total = [], 0
    for page in pages:
        if not isinstance(page, dict) or "check_runs" not in page:
            raise ValueError("not a check-runs response")
        total = max(total, int(page.get("total_count", 0)))
        runs.extend(page["check_runs"])
    return runs, total


def evaluate(required, sha, runs, total):
    problems = []
    if total != len(runs):
        problems.append(f"incomplete check-run listing ({len(runs)} of {total})")
    if not runs:
        problems.append("no check runs on this commit")
    for run in runs:
        if run.get("head_sha") != sha:
            problems.append(f"run '{run.get('name')}' belongs to another commit")
    # The latest run per name decides (re-runs replace earlier attempts).
    latest = {}
    for run in sorted(runs, key=lambda r: (r.get("started_at") or "", r.get("id") or 0)):
        latest[run.get("name", "")] = run
    for name, run in sorted(latest.items()):
        if run.get("status") != "completed":
            problems.append(f"'{name}' is {run.get('status')}")
        elif run.get("conclusion") not in OK:
            problems.append(f"'{name}' concluded {run.get('conclusion')}")
    for pattern in required:
        matched = [name for name in latest if fnmatch.fnmatchcase(name, pattern)]
        if not matched:
            problems.append(f"required check '{pattern}' did not run")
            continue
        for name in matched:
            if latest[name].get("conclusion") != "success":
                problems.append(f"required check '{name}' is not successful")
    return problems, latest


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: provenance.py <required-checks file> <sha> < check-runs.json", file=sys.stderr)
        return 2
    sha = sys.argv[2]
    if len(sha) != 40 or any(c not in "0123456789abcdef" for c in sha):
        print("FAIL the SHA must be a full 40-character lowercase commit SHA")
        return 1
    with open(sys.argv[1], encoding="utf-8") as handle:
        required = [line.strip() for line in handle if line.strip() and not line.startswith("#")]
    if not required:
        print("FAIL the required-checks list is empty")
        return 1
    try:
        runs, total = load_runs(sys.stdin.read())
    except (ValueError, TypeError) as error:
        print(f"FAIL unreadable check-run data ({error})")
        return 1
    problems, latest = evaluate(required, sha, runs, total)
    for name, run in sorted(latest.items()):
        print(f"check {run.get('conclusion') or run.get('status')}: {name}")
    if problems:
        for problem in problems:
            print(f"FAIL {problem}")
        return 1
    print(f"PASS all {len(latest)} check runs on {sha[:12]} are green, {len(required)} required present")
    return 0


if __name__ == "__main__":
    sys.exit(main())

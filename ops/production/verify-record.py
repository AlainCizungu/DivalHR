#!/usr/bin/env python3
"""LAUNCH-002 / R231-2: the production deploy gate's verification-record check.

Used by BOTH the workstation (deploy.sh) and the host (remote-deploy.sh), so the same rules apply
at both places. It accepts exactly one run directory and nothing else:

  verify-record.py --release SHA --bundle release.bundle --run-dir DIR
                   [--max-age-hours N] [--expect-evidence-sha256 HEX] [--restore-mode]

1. only DIR is copied into a fresh temporary runs directory, so a sibling run can never be the one
   the evidence decision accepts (the decision is re-run on that copy and must ACCEPT exactly DIR);
2. the record must be a FULL-profile record of the exact release (run id, commit, tree), with the
   complete classification, every full-profile stage and rehearsal step PASS, zero Core failures or
   errors, zero browser failed/flaky/retried/not-run tests, hygiene PASS, checksum and read-only
   mode intact, and fresh (measured from the record's own timestamps, not a directory mtime);
3. on the host, --expect-evidence-sha256 (sent by the workstation, outside the S3 round trip)
   must equal the record's SHA-256 and --restore-mode then re-applies the read-only mode that the
   S3 transfer drops, before the decision runs.

Prints one line per check; exit 0 only when every check passes. Never writes outside the temp dir
(except the 0444 restore on the given run dir in --restore-mode).
"""
from __future__ import annotations

import datetime
import hashlib
import importlib.util
import json
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))


def load_evidence():
    path = os.path.join(HERE, "..", "hr-dev", "evidence.py")
    spec = importlib.util.spec_from_file_location("hrdev_evidence", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules["hrdev_evidence"] = module
    spec.loader.exec_module(module)
    return module


def arg(argv, name, default=None):
    return argv[argv.index(name) + 1] if name in argv and argv.index(name) + 1 < len(argv) else default


def fail(why):
    print(f"REJECT {why}")
    return 1


def parse_ts(value):
    return datetime.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=datetime.timezone.utc)


def main(argv):
    sha, bundle, run_dir = arg(argv, "--release"), arg(argv, "--bundle"), arg(argv, "--run-dir")
    if not sha or not bundle or not run_dir:
        print(__doc__, file=sys.stderr)
        return 2
    max_age = float(arg(argv, "--max-age-hours", "72"))
    expect = arg(argv, "--expect-evidence-sha256")
    run_dir = os.path.normpath(run_dir)
    name = os.path.basename(run_dir)
    ev = load_evidence()

    if os.path.islink(run_dir) or not os.path.isdir(run_dir):
        return fail("the verification run is not a plain directory")
    if not ev.RUN_ID_RE.fullmatch(name):
        return fail("the directory name is not a verification run id")
    rec_path = os.path.join(run_dir, "evidence.json")
    side_path = os.path.join(run_dir, "evidence.sha256")
    for path in (rec_path, side_path):
        if os.path.islink(path) or not os.path.isfile(path):
            return fail(f"{os.path.basename(path)} is missing or not a regular file")
    digest = hashlib.sha256(open(rec_path, "rb").read()).hexdigest()
    if expect is not None:
        if digest != expect:
            return fail("evidence.json is not the record the workstation verified (SHA-256 differs)")
        print("PASS evidence.json equals the checksum the workstation verified")
        if "--restore-mode" in argv:
            for path in (rec_path, side_path):
                os.chmod(path, 0o444)

    # 1. decide on a copy that holds ONLY this run
    with tempfile.TemporaryDirectory() as tmp:
        runs = os.path.join(tmp, "runs")
        os.mkdir(runs)
        for root, dirs, files in os.walk(run_dir):
            for entry in dirs + files:
                if os.path.islink(os.path.join(root, entry)):
                    return fail("the run directory contains a symbolic link")
        shutil.copytree(run_dir, os.path.join(runs, name), symlinks=False)
        try:
            release = ev.release_from_bundle(sha, bundle, None)
        except Exception as error:  # noqa: BLE001 - any failure is a rejection
            return fail(f"the release bundle is not usable: {error}")
        release_tree = release.tree
        accepted, verdicts = ev.decide(release, runs)
        if accepted is None or accepted.run != name:
            why = "; ".join(f"{v.run}: {v.reason}" for v in verdicts) or "no record"
            return fail(f"the evidence decision does not accept exactly this run ({why})")
        print(f"PASS the evidence decision accepts exactly {name}")

    record = json.loads(open(rec_path, encoding="utf-8").read())
    if record.get("profile") != "full":
        return fail(f"profile is {record.get('profile')!r}; only a full verification qualifies for production")
    print("PASS profile is full")
    commit = record.get("commit")
    if record.get("runId") != name or not isinstance(commit, str) or name[-12:] != commit[:12]:
        return fail("the record's run id does not name its own commit")
    if not release_tree or record.get("tree") != release_tree:
        return fail("the verified tree is not the release tree")
    print(f"PASS run id, commit {commit[:12]} and tree {release_tree[:12]} are those of the release")

    cls = record.get("classification")
    if not isinstance(cls, dict) or cls.get("class") != ev.COMPLETE or not isinstance(cls.get("reasons"), list):
        return fail("the record's classification is not complete")
    if record.get("rehearsalClass") != ev.COMPLETE:
        return fail("the rehearsal did not run the complete class")
    steps = record.get("rehearsalSteps") or {}
    for needed in ev.COMPLETE_STEPS:
        if steps.get(needed) != "PASS":
            return fail(f"rehearsal step {needed} is {steps.get(needed, 'missing')}")
    stages = record.get("stages") or {}
    for needed in ("core-check", "e2e", "hrdev-rehearsal"):
        if stages.get(needed) != "PASS":
            return fail(f"stage {needed} is {stages.get(needed, 'missing')}")
    print(f"PASS classification complete, {len(ev.COMPLETE_STEPS)} rehearsal steps and every stage PASS")

    core = record.get("coreTests") or {}
    if not all(isinstance(core.get(k), int) for k in ("total", "failures", "errors")) \
            or core["total"] <= 0 or core["failures"] or core["errors"]:
        return fail(f"Core tests are not clean: {core}")
    browser = record.get("browserTests") or {}
    keys = ("passed", "failed", "flaky", "notrun", "retries")
    if not all(isinstance(browser.get(k), int) for k in keys) or browser["passed"] <= 0 \
            or any(browser[k] for k in ("failed", "flaky", "notrun", "retries")):
        return fail(f"browser tests are not clean: {browser}")
    if browser.get("hygiene") != "PASS":
        return fail("the credential-hygiene check did not pass")
    print("PASS Core clean, browser clean (no failed, flaky, retried or not-run test), hygiene PASS")

    try:
        stamps = [parse_ts(record[k]) for k in ("finishedAt", "recordedAt")]
    except (KeyError, ValueError):
        return fail("the record has no valid finishedAt/recordedAt")
    age_h = (datetime.datetime.now(datetime.timezone.utc) - min(stamps)).total_seconds() / 3600
    if age_h < 0 or age_h > max_age:
        return fail(f"the record is {age_h:.1f} h old (limit {max_age:g} h)")
    print(f"PASS the record is fresh ({age_h:.1f} h, limit {max_age:g} h)")
    print(f"ACCEPT {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

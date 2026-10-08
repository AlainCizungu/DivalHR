#!/usr/bin/env python3
"""DEVX-001B (Issue #75; A75-5, A75-6, A75B-1..A75B-6): risk classes, rehearsal steps,
checksummed read-only verification records and the hr-dev deployment decision.

    evidence.py classify --repo DIR --head SHA [--base SHA] [--deployed SHA]
        prints `class=<class>` and `reason=` lines. Classes: docs-only, app, migration,
        identity, migration+identity, complete. Anything uncertain is `complete`.
    evidence.py steps --class CLASS
        the rehearsal steps the class requires, one per line (the canonical matrix).
    evidence.py record --run-dir DIR --exit N
        writes DIR/evidence.json (temporary file, fsync, rename, 0444) and DIR/evidence.sha256.
    evidence.py decide --release SHA --release-bundle FILE --runs DIR [--deployed SHA]
        the deployment decision for hr-dev: `ACCEPT <run>` (exit 0) or `REJECT` with every
        rejected run's first failed rule and the verification to run instead (exit 1).

Standard library only. Git is always called with argument lists and NUL-delimited output, never
through a shell. The records are checksummed and read-only, not cryptographically immutable: they
detect accidental change, truncation, failed runs and mismatched code, and they trust the
controlled host and its operator (docs/OPS-HR-DEV.md section 6).
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from datetime import datetime, timezone

SCHEMA = 1
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
RUN_ID_RE = re.compile(r"^[0-9]{8}T[0-9]{6}Z-([0-9a-f]{12})$")

# --- risk classes (A75-5, A75B-1) -----------------------------------------------------------------

DOCS, APP, MIGRATION, IDENTITY, COMPLETE = "docs-only", "app", "migration", "identity", "complete"

# Precedence: every path is tested against COMPLETE first, then IDENTITY, MIGRATION, APP and DOCS;
# a path no rule matches is COMPLETE. The release class is the union over all paths.
COMPLETE_RULES = [
    (r"(^|/)Dockerfile[^/]*$", "a Dockerfile"),
    (r"(^|/)(docker-)?compose[^/]*\.ya?ml$", "a Compose file"),
    (r"(^|/)\.dockerignore$", "a Docker build context file"),
    (r"^\.github/", "a workflow or repository automation file"),
    (r"^ops/", "operations code"),
    (r"^scripts/", "a verification or operations script"),
    (r"^infrastructure/(?!docker/keycloak/|hr-dev/keycloak/)", "deployment infrastructure"),
    (r"(^|/)package\.json$|^pnpm-lock\.yaml$|^pnpm-workspace\.yaml$|^\.npmrc$|^\.nvmrc$",
     "a JavaScript dependency or toolchain file"),
    (r"(^|/)(build|settings)\.gradle(\.kts)?$|(^|/)gradle\.properties$|(^|/)gradlew(\.bat)?$"
     r"|(^|/)gradle/", "a Gradle build file"),
    (r"(^|/)pyproject\.toml$|(^|/)uv\.lock$|(^|/)requirements[^/]*\.txt$",
     "a Python dependency file"),
    (r"^apps/web/(vite\.config\.ts|tsconfig[^/]*\.json|index\.html)$", "web build configuration"),
    (r"^apps/web/docker/", "web server configuration"),
    (r"^apps/web/(playwright\.hr-dev\.config\.ts|e2e/hr-dev/)", "the hr-dev acceptance suite"),
    (r"^(Makefile|\.env\.example)$", "build or environment configuration"),
]
IDENTITY_RULES = [
    (r"^infrastructure/(docker|hr-dev)/keycloak/", "the identity provider realm or image"),
    (r"^apps/keycloak-provisioning/", "the identity provisioning extension"),
    (r"^apps/core-api/src/main/java/com/divalhr/core/identity/", "Core identity code"),
    (r"^apps/core-api/src/main/java/com/divalhr/core/platform/security/", "Core security code"),
    (r"^apps/core-api/src/main/java/com/divalhr/core/platform/tenancy/", "Core tenant resolution"),
    (r"^apps/core-api/src/main/java/com/divalhr/core/tenant/api/AuthenticatedCaller\.java$",
     "the Core authenticated caller"),
    (r"^apps/core-api/src/main/resources/application[^/]*\.ya?ml$",
     "Core configuration (OIDC, security, limits)"),
    (r"^apps/web/src/(auth|pwa)/", "the web sign-in or service-worker code"),
]
MIGRATION_RULES = [
    (r"^apps/core-api/src/main/resources/db/", "a database migration"),
]
APP_RULES = [
    (r"^apps/core-api/src/(main|test)/", "Core code or tests"),
    (r"^apps/core-api/config/", "Core static-analysis configuration"),
    (r"^apps/web/(src|public)/", "web code"),
    (r"^apps/web/e2e/([^/]+|evidence/[^/]+)$", "browser tests"),
    (r"^apps/web/(playwright\.config\.ts|eslint\.config\.js)$", "web test or lint configuration"),
    (r"^apps/ai-service/(src|tests)/", "AI service code or tests"),
    (r"^packages/[^/]+/(src|locales|openapi|schemas|scripts)/", "a shared package"),
    (r"^packages/[^/]+/tsconfig\.json$", "a shared package"),
    (r"^docs/API-SPEC\.yaml$", "the API contract"),
]
DOCS_RULES = [
    (r"^docs/", "documentation"),
    (r"(^|/)[^/]+\.md$", "documentation"),
]
_RULES = [(COMPLETE, COMPLETE_RULES), (IDENTITY, IDENTITY_RULES), (MIGRATION, MIGRATION_RULES),
          (APP, APP_RULES), (DOCS, DOCS_RULES)]
_COMPILED = [(kind, [(re.compile(p), why) for p, why in rules]) for kind, rules in _RULES]


def classify_path(path: str) -> tuple[str, str]:
    """The class of one path and why; precedence COMPLETE > IDENTITY > MIGRATION > APP > DOCS."""
    if not path or path.startswith("/") or "\x00" in path or "\n" in path:
        return COMPLETE, "an unreadable path"
    for kind, rules in _COMPILED:
        for pattern, why in rules:
            if pattern.search(path):
                return kind, why
    return COMPLETE, "an unclassified path"


def combine(kinds: set[str]) -> str:
    """The class covering every kind (or combined class) in the set; anything unknown is complete."""
    kinds = {part for kind in kinds for part in kind.split("+")}
    if not kinds or COMPLETE in kinds or not kinds <= {DOCS, APP, MIGRATION, IDENTITY}:
        return COMPLETE
    risky = kinds & {MIGRATION, IDENTITY}
    if risky == {MIGRATION, IDENTITY}:
        return "migration+identity"
    if risky:
        return risky.pop()
    if APP in kinds:
        return APP
    return DOCS


def classify_paths(paths: list[str]) -> tuple[str, list[str]]:
    if not paths:
        return COMPLETE, ["complete: empty change set"]
    kinds, reasons = set(), []
    for path in paths:
        kind, why = classify_path(path)
        kinds.add(kind)
        if kind != DOCS and kind != APP:
            reasons.append(f"{kind}: {why}")
    result = combine(kinds)
    return result, sorted(set(reasons)) or [f"{result}: every path"]


def _git(repo: str, *args: str) -> bytes:
    return subprocess.run(["git", "-C", repo, *args], check=True, capture_output=True).stdout


def changed_paths(repo: str, old: str, new: str) -> list[str]:
    """Old and new paths of every change between two commits (renames reported as a deletion and
    an addition, so both sides are classified), NUL-delimited, never shell-evaluated."""
    out = _git(repo, "diff", "-z", "--name-only", "--no-renames", "--no-ext-diff", old, new)
    return [p.decode("utf-8", "replace") for p in out.split(b"\x00") if p]


def is_ancestor(repo: str, old: str, new: str) -> bool:
    return subprocess.run(["git", "-C", repo, "merge-base", "--is-ancestor", old, new],
                          capture_output=True).returncode == 0


def classify_diffs(repo: str, head: str, base: str | None, deployed: str | None) -> tuple[str, list[str]]:
    """The broadest class of base...head and deployed..head; anything uncertain is complete."""
    if not SHA_RE.match(head or ""):
        return COMPLETE, ["complete: the candidate is not a full SHA"]
    kinds, reasons = set(), []
    pairs = []
    if base is not None:
        pairs.append(("base", base))
    pairs.append(("deployed release", deployed))
    for label, old in pairs:
        if not old:
            return COMPLETE, [f"complete: no {label} (first deployment or unknown)"]
        if not SHA_RE.match(old):
            return COMPLETE, [f"complete: the {label} is not a full SHA"]
        try:
            if not is_ancestor(repo, old, head):
                return COMPLETE, [f"complete: the {label} is not an ancestor of the candidate"]
            paths = changed_paths(repo, old, head)
        except (subprocess.CalledProcessError, OSError):
            return COMPLETE, [f"complete: the {label} diff is unreadable"]
        kind, why = classify_paths(paths)
        kinds.add(kind)
        reasons += [f"{label}: {r}" for r in why]
    return combine(kinds), reasons


# --- rehearsal steps (the canonical matrix; A75B-2) ------------------------------------------------

BASE_STEPS = ["deploy-first", "browser-suite", "realm-verify-final"]
MIGRATION_STEPS = ["backup", "restore-drill", "rollback-migration"]
IDENTITY_STEPS = ["rollback-identity-change"]
COMPLETE_STEPS = ["first-deploy-units-failure", "deploy-first", "browser-suite", "realm-verify-final",
                  "backup", "host-lock-concurrency", "watchdog-failure-injection", "restore-drill",
                  "upgrade-units-failure", "rollback-identity-change", "rollback-migration"]
CLASSES = [DOCS, APP, MIGRATION, IDENTITY, "migration+identity", COMPLETE]


def required_steps(cls: str) -> list[str]:
    """The rehearsal steps a class requires, in rehearsal order. Unknown classes require all."""
    if cls == DOCS:
        return []
    wanted = set(BASE_STEPS)
    if cls in (MIGRATION, "migration+identity"):
        wanted |= set(MIGRATION_STEPS)
    if cls in (IDENTITY, "migration+identity"):
        wanted |= set(IDENTITY_STEPS)
    if cls not in CLASSES or cls == COMPLETE:
        wanted = set(COMPLETE_STEPS)
    return [s for s in COMPLETE_STEPS if s in wanted]


def deployment_class(release_class: str) -> str:
    """A deployment never needs less than the app rehearsal (A75B-6: no docs-only bypass)."""
    return APP if release_class == DOCS else release_class


# --- records (A75B-3, A75B-4) ----------------------------------------------------------------------

QUALIFYING_PROFILES = {"pr", "full"}


def normalize_profile(requested: str) -> str:
    """The recorded profile: `full` and the legacy `all` are `full`; `pr` is `pr`; everything
    else keeps its own, non-qualifying name."""
    requested = (requested or "").strip()
    if requested in ("full", "all"):
        return "full"
    return requested or "unknown"


def _read(path: str) -> str:
    try:
        with open(path, encoding="utf-8") as handle:
            return handle.read()
    except OSError:
        return ""


def _results(text: str) -> dict[str, str]:
    """`PASS name` / `FAIL name` / `SKIP name (...)` lines; a later line for a name wins only if it
    is worse (FAIL beats SKIP beats PASS), so a name can never be upgraded."""
    rank = {"PASS": 0, "SKIP": 1, "FAIL": 2}
    out: dict[str, str] = {}
    for line in text.splitlines():
        m = re.match(r"^(PASS|FAIL|SKIP) ([A-Za-z0-9][A-Za-z0-9._-]*)(?: \(.*\))?$", line.strip())
        if not m:
            continue
        result, name = m.group(1), m.group(2)
        if name not in out or rank[result] > rank[out[name]]:
            out[name] = result
    return out


def _sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def bundle_head(bundle: str) -> str | None:
    """The single commit a bundle names, or None (several heads, none, unreadable)."""
    try:
        out = subprocess.run(["git", "bundle", "list-heads", bundle], check=True,
                             capture_output=True, text=True).stdout
    except (subprocess.CalledProcessError, OSError):
        return None
    heads = {line.split()[0] for line in out.splitlines() if line.strip()}
    if len(heads) != 1:
        return None
    head = heads.pop()
    return head if SHA_RE.match(head) else None


def bundle_tree(bundle: str, commit: str) -> str | None:
    """tree(commit) recomputed from a bundle in a throw-away repository."""
    with tempfile.TemporaryDirectory() as tmp:
        try:
            subprocess.run(["git", "init", "-q", "--bare", tmp], check=True, capture_output=True)
            subprocess.run(["git", "-C", tmp, "fetch", "-q", bundle, "+refs/*:refs/bundle/*"],
                           check=True, capture_output=True)
            tree = subprocess.run(["git", "-C", tmp, "rev-parse", "--verify", f"{commit}^{{tree}}"],
                                  check=True, capture_output=True, text=True).stdout.strip()
        except (subprocess.CalledProcessError, OSError):
            return None
    return tree if SHA_RE.match(tree) else None


def build_record(run_dir: str, exit_code: int) -> dict:
    run_id = os.path.basename(os.path.normpath(run_dir))
    logs = os.path.join(run_dir, "logs")
    stage = _read(os.path.join(run_dir, "stage")).strip()
    profile = normalize_profile(_read(os.path.join(run_dir, "profile")).strip() or stage)
    summary = _read(os.path.join(logs, f"{stage}.summary")) if stage else ""
    rehearsal = _read(os.path.join(logs, "rehearsal.summary"))
    bundle = os.path.join(run_dir, "verify.bundle")
    commit = bundle_head(bundle) if os.path.isfile(bundle) else None
    m = re.search(r"^CLASS (\S+)$", rehearsal, re.M)
    tests = re.search(r"^TESTS core-api total=(\d+) failures=(\d+) errors=(\d+) skipped=(\d+)$",
                      summary, re.M)
    return {
        "schema": SCHEMA,
        "runId": run_id,
        "profile": profile,
        "stage": stage,
        "exit": exit_code,
        "commit": commit,
        "tree": bundle_tree(bundle, commit) if commit else None,
        "base": _read(os.path.join(run_dir, "base")).strip() or None,
        "bundleSha256": _sha256(bundle) if os.path.isfile(bundle) else None,
        "rehearsalClass": m.group(1) if m else None,
        "rehearsalSteps": {k: v for k, v in _results(rehearsal).items() if k in COMPLETE_STEPS},
        "stages": _results(summary),
        "coreTests": dict(zip(("total", "failures", "errors", "skipped"),
                              map(int, tests.groups()), strict=True)) if tests else None,
        "recordedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def write_record(run_dir: str, record: dict) -> str:
    """Writes evidence.json once (temporary file, fsync, rename, 0444) and evidence.sha256."""
    target = os.path.join(run_dir, "evidence.json")
    if os.path.lexists(target):
        raise FileExistsError("evidence.json already exists")
    data = (json.dumps(record, indent=2, sort_keys=True) + "\n").encode("utf-8")
    fd, tmp = tempfile.mkstemp(prefix=".evidence.", dir=run_dir)
    try:
        os.write(fd, data)
        os.fsync(fd)
    finally:
        os.close(fd)
    os.chmod(tmp, 0o444)
    os.rename(tmp, target)
    digest = hashlib.sha256(data).hexdigest()
    side = os.path.join(run_dir, "evidence.sha256")
    fd, tmp = tempfile.mkstemp(prefix=".evidence-sha.", dir=run_dir)
    try:
        os.write(fd, f"{digest}  evidence.json\n".encode())
        os.fsync(fd)
    finally:
        os.close(fd)
    os.chmod(tmp, 0o444)
    os.rename(tmp, side)
    dir_fd = os.open(run_dir, os.O_RDONLY)
    try:
        os.fsync(dir_fd)
    finally:
        os.close(dir_fd)
    return digest


# --- the deployment decision (A75-6, A75B-2..A75B-6) -----------------------------------------------

@dataclass
class Verdict:
    run: str
    ok: bool
    reason: str
    finished: str = ""
    profile: str = ""


@dataclass
class Release:
    sha: str
    tree: str | None
    cls: str
    reasons: list[str] = field(default_factory=list)


def _regular_readonly(path: str) -> str | None:
    """None when path is a regular file (not a link) with mode 0444; otherwise the problem."""
    try:
        st = os.lstat(path)
    except OSError:
        return "missing"
    if not stat.S_ISREG(st.st_mode):
        return "not a regular file"
    if stat.S_IMODE(st.st_mode) != 0o444:
        return f"mode {oct(stat.S_IMODE(st.st_mode))[2:]}, not 444"
    return None


def evaluate_run(run_dir: str, release: Release) -> Verdict:
    """The first failed rule for one run directory, or ok."""
    name = os.path.basename(os.path.normpath(run_dir))
    v = lambda ok, why, **kw: Verdict(name, ok, why, **kw)  # noqa: E731
    m = RUN_ID_RE.match(name)
    if not m:
        return v(False, "the directory name is not a run ID")
    rec_path, side_path = os.path.join(run_dir, "evidence.json"), os.path.join(run_dir, "evidence.sha256")
    for label, path in (("evidence.json", rec_path), ("evidence.sha256", side_path)):
        problem = _regular_readonly(path)
        if problem:
            return v(False, f"{label} is {problem}")
    side = _read(side_path).split()
    if len(side) != 2 or side[1] != "evidence.json" or not re.fullmatch(r"[0-9a-f]{64}", side[0]):
        return v(False, "evidence.sha256 is malformed")
    if _sha256(rec_path) != side[0]:
        return v(False, "evidence.json does not match its checksum")
    try:
        record = json.loads(_read(rec_path))
    except ValueError:
        return v(False, "evidence.json does not parse")
    if not isinstance(record, dict) or record.get("schema") != SCHEMA:
        return v(False, "unknown record schema")
    finished = str(record.get("recordedAt", ""))
    profile = str(record.get("profile", ""))
    if record.get("runId") != name:
        return v(False, "the record's run ID is not its directory")
    if record.get("exit") != 0:
        return v(False, f"the run failed (exit {record.get('exit')})", finished=finished, profile=profile)
    if profile not in QUALIFYING_PROFILES:
        return v(False, f"profile {profile or 'unknown'} never qualifies", finished=finished, profile=profile)
    if record.get("diagnosticOverride"):
        return v(False, "the run used a diagnostic override", finished=finished, profile=profile)
    commit = record.get("commit")
    if not isinstance(commit, str) or not SHA_RE.match(commit):
        return v(False, "the record has no full commit SHA", finished=finished, profile=profile)
    if m.group(1) != commit[:12]:
        return v(False, "the run ID does not name the record's commit", finished=finished, profile=profile)
    bundle = os.path.join(run_dir, "verify.bundle")
    if os.path.islink(bundle) or not os.path.isfile(bundle):
        return v(False, "the verified bundle is missing", finished=finished, profile=profile)
    if _sha256(bundle) != record.get("bundleSha256"):
        return v(False, "the verified bundle does not match the record", finished=finished, profile=profile)
    if bundle_head(bundle) != commit:
        return v(False, "the verified bundle's single head is not the record's commit",
                 finished=finished, profile=profile)
    tree = bundle_tree(bundle, commit)
    if tree is None or release.tree is None or tree != release.tree:
        return v(False, "the verified tree is not the release tree", finished=finished, profile=profile)
    stages = record.get("stages") or {}
    for needed in ("core-check", "e2e", "hrdev-rehearsal"):
        if stages.get(needed) != "PASS":
            return v(False, f"stage {needed} did not pass", finished=finished, profile=profile)
    steps = record.get("rehearsalSteps") or {}
    for needed in required_steps(deployment_class(release.cls)):
        if steps.get(needed) != "PASS":
            state = steps.get(needed, "missing")
            return v(False, f"required rehearsal step {needed} is {state} "
                            f"(release class {deployment_class(release.cls)})",
                     finished=finished, profile=profile)
    return v(True, f"{profile} evidence, tree equal, every {deployment_class(release.cls)} "
                   f"rehearsal step passed", finished=finished, profile=profile)


def fallback(release: Release) -> str:
    """The verification to run when nothing qualifies (A75B-6)."""
    if release.cls in (APP, MIGRATION, IDENTITY, "migration+identity"):
        return f"aws-verify.sh {release.sha} pr"
    return f"aws-verify.sh {release.sha} full"


def decide(release: Release, runs_dir: str) -> tuple[Verdict | None, list[Verdict]]:
    verdicts = []
    try:
        names = sorted(os.listdir(runs_dir))
    except OSError:
        names = []
    for entry in names:
        path = os.path.join(runs_dir, entry)
        if os.path.islink(path) or not os.path.isdir(path):
            continue
        if not os.path.lexists(os.path.join(path, "evidence.json")):
            # Older runs and interrupted runs have no record; they never qualify (A75B-4).
            verdicts.append(Verdict(entry, False, "no evidence record"))
            continue
        verdicts.append(evaluate_run(path, release))
    accepted = [x for x in verdicts if x.ok]
    accepted.sort(key=lambda x: (x.finished, x.run), reverse=True)  # newest first (A75B-4)
    return (accepted[0] if accepted else None), verdicts


def release_from_bundle(sha: str, bundle: str, deployed: str | None) -> Release:
    if not SHA_RE.match(sha or ""):
        raise ValueError("the release must be a full 40-character SHA")
    if bundle_head(bundle) != sha:
        raise ValueError("the release bundle's single head is not the release SHA")
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["git", "init", "-q", "--bare", tmp], check=True, capture_output=True)
        subprocess.run(["git", "-C", tmp, "fetch", "-q", bundle, "+refs/*:refs/bundle/*"],
                       check=True, capture_output=True)
        tree = _git(tmp, "rev-parse", "--verify", f"{sha}^{{tree}}").decode().strip()
        has_deployed = bool(deployed) and subprocess.run(
            ["git", "-C", tmp, "cat-file", "-e", f"{deployed}^{{commit}}"],
            capture_output=True).returncode == 0
        cls, reasons = classify_diffs(tmp, sha, None, deployed if has_deployed else None)
        if deployed and not has_deployed:
            cls, reasons = COMPLETE, ["complete: the deployed release is not in the release history"]
    return Release(sha, tree, cls, reasons)


# --- command line ----------------------------------------------------------------------------------

def _arg(argv: list[str], name: str, default: str | None = None) -> str | None:
    return argv[argv.index(name) + 1] if name in argv and argv.index(name) + 1 < len(argv) else default


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__, file=sys.stderr)
        return 2
    cmd = argv[0]
    if cmd == "classify":
        repo, head = _arg(argv, "--repo"), _arg(argv, "--head")
        if not repo or not head:
            return 2
        cls, reasons = classify_diffs(repo, head, _arg(argv, "--base"), _arg(argv, "--deployed") or None)
        print(f"class={cls}")
        for reason in reasons:
            print(f"reason={reason}")
        return 0
    if cmd == "steps":
        cls = _arg(argv, "--class")
        if cls not in CLASSES:
            print(f"unknown class {cls}", file=sys.stderr)
            return 2
        print("\n".join(required_steps(cls)))
        return 0
    if cmd == "record":
        run_dir, code = _arg(argv, "--run-dir"), _arg(argv, "--exit")
        if not run_dir or code is None or not re.fullmatch(r"-?\d+", code):
            return 2
        digest = write_record(run_dir, build_record(run_dir, int(code)))
        print(f"EVIDENCE sha256={digest}")
        return 0
    if cmd == "decide":
        sha, bundle, runs = _arg(argv, "--release"), _arg(argv, "--release-bundle"), _arg(argv, "--runs")
        if not sha or not bundle or not runs:
            return 2
        try:
            release = release_from_bundle(sha, bundle, _arg(argv, "--deployed") or None)
        except (ValueError, subprocess.CalledProcessError, OSError) as error:
            print(f"REJECT {error}")
            return 1
        print(f"release class {release.cls} (requires {deployment_class(release.cls)} rehearsal steps)")
        for reason in release.reasons:
            print(f"  {reason}")
        accepted, verdicts = decide(release, runs)
        for verdict in verdicts:
            print(f"  run {verdict.run}: {'qualifies' if verdict.ok else 'rejected'}: {verdict.reason}")
        if accepted:
            print(f"ACCEPT {accepted.run} ({accepted.reason})")
            return 0
        print(f"REJECT no qualifying evidence; verify the release itself: {fallback(release)}")
        return 1
    print(f"unknown command {cmd}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

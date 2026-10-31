#!/usr/bin/env bash
# Mutation checks for payroll approval and payslips (Issue #181, MVP-094C, ADR 0035).
#
# Each mutation changes one place in production code, runs the tests that must notice it and expects
# a FAILURE of at least one of those tests. A mutation whose tests still pass SURVIVED, which means
# the evidence is too weak; the script then exits non-zero. Sources are always restored, even on
# interruption.
#
# BASELINE: before anything is mutated, every focused test class runs once on the unmodified
# sources and each must report PASSED tests. A red or empty baseline aborts the script (exit 2): in
# MVP-094B a migration failure made every test red and mutations were reported KILLED falsely.
#
# Usage: scripts/dev/payroll-package-mutations.sh [mutation-number ...]   (default: all)
#   UNIT_ONLY=1    run only the pure unit classes (no Docker); a mutation that no unit class can
#                  see is reported NOT EVALUATED and the final exit code is 3, never a pass
#   CHECK_ONLY=1   only prove that every anchor exists exactly once and restores cleanly (no Gradle)
#
# 1 approval accepts a stale source (the drift check is ignored)
# 2 a non-VERIFIED rule pack is accepted
# 3 the employer contribution is deducted from the net
# 4 the payslip employee predicate is removed from the employee-facing queries (isolation)
# 5 the void rewrites a payslip snapshot
# 6 the source lock is bypassed (the supersession answer of the older modules is removed)
# 7 the PDF metadata leaks the employee identifier
# 8 the tenant predicate is removed from the chain read (version read)
# 9 NO_CHANGE is reported as a stale preview
# 10 a VOIDED payslip is shown as APPROVED to the employee
# 11 the 15-minute expiry is ignored (approval and void)
#
# Needs a working Gradle build of apps/core-api; the integration classes need Docker/Testcontainers
# and are written by the integration test worker (PayrollPackage*IntegrationTest, PayslipPdf*).
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CORE="$ROOT/apps/core-api"
SRC="$CORE/src/main/java/com/divalhr/core/payroll"
BACKUP="$(mktemp -d)"
trap 'restore_all' EXIT INT TERM

declare -a TOUCHED=()

restore_all() {
  for f in "${TOUCHED[@]:-}"; do
    [ -n "$f" ] && [ -f "$BACKUP/$(basename "$f")" ] && cp "$BACKUP/$(basename "$f")" "$f"
  done
}

# mutate FILE OLD NEW : replaces exactly one occurrence, or fails. The first backup of a file is
# kept, so several mutations of one file restore to the original.
mutate() {
  local file="$1" old="$2" new="$3"
  [ -f "$BACKUP/$(basename "$file")" ] || cp "$file" "$BACKUP/$(basename "$file")"
  TOUCHED+=("$file")
  python3 - "$file" "$old" "$new" <<'PY'
import sys
path, old, new = sys.argv[1:4]
text = open(path, encoding="utf-8").read()
if text.count(old) != 1:
    sys.exit("mutation anchor not found exactly once in " + path + ": " + old)
open(path, "w", encoding="utf-8").write(text.replace(old, new))
PY
}

UNIT="PayslipSnapshotTest PackageDigestsTest PayrollApprovalEvaluatorTest PayslipTextTest \
PayslipPdfRendererTest PayrollPackageErrorCodesTest PayrollPackageConstraintViolationsTest \
JdbcPayrollPackageRepositoryTenantTest PayrollPackageArchitectureTest \
PayrollPackageEventsNotDeliveredTest"
INTEGRATION="PayrollPackageApiIntegrationTest PayrollPackageAuthorityIntegrationTest \
PayrollPackageConcurrencyIntegrationTest PayrollPackagePrivacyIntegrationTest \
PayrollPackageRateLimitIntegrationTest PayrollPackageQueryPlanIntegrationTest \
PayrollPackageMigrationIntegrationTest PayslipPdfIntegrationTest"

# Gradle prints one "<class> > <method> FAILED|PASSED" line per test (build.gradle.kts testLogging).
FAILED_RE='^[A-Za-z0-9_.$]+ > .* FAILED'

gradle_test() {
  local log="$1"
  shift
  local args=()
  for t in "$@"; do args+=(--tests "*$t"); done
  (cd "$CORE" && ./gradlew --no-daemon test "${args[@]}" >"$log" 2>&1)
}

survivors=0
skipped=0

baseline() {
  local classes=($UNIT)
  [ "${UNIT_ONLY:-}" ] || classes+=($INTEGRATION)
  echo "== baseline: ${#classes[@]} focused test classes on the unmodified sources"
  local log; log="$(mktemp)"
  if ! gradle_test "$log" "${classes[@]}"; then
    echo "   BASELINE IS RED: no mutation can be judged. See $log"
    grep -E -A25 "$FAILED_RE" "$log" | head -120
    exit 2
  fi
  for c in "${classes[@]}"; do
    if ! grep -qE "\.$c > .* PASSED" "$log"; then
      echo "   BASELINE RAN NO PASSING TEST OF $c (missing, skipped or not compiled). See $log"
      exit 2
    fi
  done
  echo "   baseline green"
}

run() {
  local number="$1" name="$2" unit_tests="$3" integration_tests="$4"
  shift 4
  if [ "${ONLY:-}" ] && [[ " $ONLY " != *" $number "* ]]; then return; fi
  echo "== mutation $number: $name"
  if ! "$@"; then
    echo "   cannot apply the mutation"
    survivors=$((survivors + 1))
    restore_all
    TOUCHED=()
    return
  fi
  if [ "${CHECK_ONLY:-}" ]; then
    echo "   anchors OK"
    restore_all
    TOUCHED=()
    return
  fi
  local tests="$unit_tests"
  [ "${UNIT_ONLY:-}" ] || tests="$unit_tests $integration_tests"
  if [ -z "${tests// /}" ]; then
    echo "   NOT EVALUATED: no unit class sees this mutation (run without UNIT_ONLY)"
    skipped=$((skipped + 1))
    restore_all
    TOUCHED=()
    return
  fi
  local log; log="$(mktemp)"
  # shellcheck disable=SC2086
  if gradle_test "$log" $tests; then
    echo "   SURVIVED (tests still pass): $tests"
    survivors=$((survivors + 1))
  elif grep -qE "$FAILED_RE" "$log"; then
    echo "   KILLED by:"
    grep -E "$FAILED_RE" "$log" | sed 's/^/     /' | head -12
    echo "   killer classes: $(grep -E "$FAILED_RE" "$log" | sed -E 's/ > .*//; s/.*\.//' | sort -u | tr '\n' ' ')"
  else
    echo "   NOT A VALID KILL (no test failed; probably a compile error). See $log"
    survivors=$((survivors + 1))
    cp "$log" "${MUTATION_LOG_DIR:-/tmp}/mutation-$number-invalid.log" 2>/dev/null
  fi
  restore_all
  TOUCHED=()
}

ONLY="$*"

if [ -z "${CHECK_ONLY:-}" ]; then
  baseline
fi

mutate_stale_source() {
  mutate "$SRC/application/PayrollApprovalEvidence.java" \
    'boolean sourceMatches = sourceDigest.equals(calculation.sourceRunDigest());' \
    'boolean sourceMatches = true;' &&
  mutate "$SRC/application/PayrollApprovalEvidence.java" \
    'sourceMatches &= fresh.isPresent() && fresh.get().inputDigest().equals(run.inputDigest());' \
    'sourceMatches &= true;'
}

run 1 "approval accepts a stale source: the drift check is ignored" \
  "" \
  "PayrollPackageApiIntegrationTest PayrollPackageConcurrencyIntegrationTest" \
  mutate_stale_source

mutate_unverified_pack() {
  mutate "$SRC/application/PayrollApprovalEvidence.java" \
    'pack != null && "VERIFIED".equals(pack.status()),' \
    'pack != null,' &&
  mutate "$SRC/domain/PayrollApprovalEvaluator.java" \
    '} else if (!facts.packVerified()) {' \
    '} else if (false) {'
}

run 2 "a non-VERIFIED rule pack is accepted" \
  "PayrollApprovalEvaluatorTest" \
  "PayrollPackageApiIntegrationTest" \
  mutate_unverified_pack

run 3 "the employer contribution is deducted from the net" \
  "PayslipSnapshotTest PayslipPdfRendererTest" \
  "PayrollPackageApiIntegrationTest PayslipPdfIntegrationTest" \
  mutate "$SRC/domain/PayslipSnapshot.java" \
  'grossMinor - deductions,' \
  'grossMinor - deductions - employer,'

mutate_employee_predicate() {
  mutate "$SRC/internal/JdbcPayrollPackageRepository.java" \
    '" WHERE s.tenant_id = :tenant AND s.employee_id = :employee AND s.id = :id")' \
    '" WHERE s.tenant_id = :tenant AND s.id = :id")' &&
  mutate "$SRC/internal/JdbcPayrollPackageRepository.java" \
    '                  + " WHERE s.tenant_id = :tenant AND s.employee_id = :employee"
                  + " ORDER BY s.approved_at DESC, s.id DESC LIMIT :limit")' \
    '                  + " WHERE s.tenant_id = :tenant"
                  + " ORDER BY s.approved_at DESC, s.id DESC LIMIT :limit")'
}

run 4 "the payslip employee predicate is removed from the employee-facing queries" \
  "JdbcPayrollPackageRepositoryTenantTest" \
  "PayrollPackagePrivacyIntegrationTest PayrollPackageAuthorityIntegrationTest PayrollPackageApiIntegrationTest" \
  mutate_employee_predicate

run 5 "the void rewrites a payslip snapshot" \
  "JdbcPayrollPackageRepositoryTenantTest" \
  "PayrollPackageApiIntegrationTest PayrollPackageMigrationIntegrationTest" \
  mutate "$SRC/internal/JdbcPayrollPackageRepository.java" \
  'public int markVoided(TenantId tenant, UUID legalEntityId, UUID packageId, Instant voidedAt) {' \
  'public int markVoided(TenantId tenant, UUID legalEntityId, UUID packageId, Instant voidedAt) {
    jdbc.sql(
            "UPDATE payroll.payroll_payslip SET employee_number = employee_number || '"'"'x'"'"'"
                + " WHERE tenant_id = :tenant AND package_id = :id")
        .param("tenant", tenant.value())
        .param("id", packageId)
        .update();'

# The database trigger payroll_package_source_locked is the real guard; the application answers its
# violation with the stable 409 (PAYROLL_PACKAGE_ALREADY_APPROVED). Removing that answer turns a
# supersession of a bound source into an unmapped integrity failure.
mutate_source_lock() {
  mutate "$SRC/internal/PayrollRunConstraintViolations.java" \
    'if ("payroll_package_source_locked".equals(constraint)) {' \
    'if (false) {' &&
  mutate "$SRC/internal/StatutoryConstraintViolations.java" \
    'if ("payroll_package_source_locked".equals(constraint)) {' \
    'if (false) {'
}

run 6 "the source lock is bypassed: the supersession answer of the run and statutory modules is gone" \
  "PayrollPackageConstraintViolationsTest" \
  "PayrollPackageApiIntegrationTest PayrollPackageConcurrencyIntegrationTest PayrollPackageMigrationIntegrationTest" \
  mutate_source_lock

run 7 "the PDF metadata leaks the employee identifier" \
  "PayslipPdfRendererTest" \
  "PayslipPdfIntegrationTest PayrollPackagePrivacyIntegrationTest" \
  mutate "$SRC/application/PayslipPdfRenderer.java" \
  '+ textString(PayslipText.documentTitle(request.locale(), request.voided()))' \
  '+ textString(PayslipText.documentTitle(request.locale(), request.voided()) + " " + request.snapshot().employeeId())'

run 8 "the tenant predicate is removed from the chain (version) read" \
  "JdbcPayrollPackageRepositoryTenantTest" \
  "PayrollPackageApiIntegrationTest PayrollPackageAuthorityIntegrationTest PayrollPackagePrivacyIntegrationTest" \
  mutate "$SRC/internal/JdbcPayrollPackageRepository.java" \
  '" WHERE p.tenant_id = :tenant AND p.legal_entity_id = :entity"
                + " AND p.period_start = :period AND p.currency = :currency ORDER BY p.version")' \
  '" WHERE true AND p.legal_entity_id = :entity"
                + " AND p.period_start = :period AND p.currency = :currency ORDER BY p.version")'

run 9 "an identical approval is reported as a stale preview instead of no change" \
  "" \
  "PayrollPackageApiIntegrationTest PayrollPackageConcurrencyIntegrationTest" \
  mutate "$SRC/application/PayrollApprovalService.java" \
  'throw new ApiException(ErrorCode.PAYROLL_APPROVAL_NO_CHANGE, Map.of());' \
  'throw new ApiException(ErrorCode.PAYROLL_APPROVAL_PREVIEW_STALE, Map.of());'

mutate_voided_as_approved() {
  mutate "$SRC/application/PayslipService.java" \
    'boolean voided = "VOIDED".equals(row.packageStatus());' \
    'boolean voided = false;' &&
  mutate "$SRC/application/PayrollPackageViews.java" \
    'boolean voided = "VOIDED".equals(row.packageStatus());' \
    'boolean voided = false;'
}

run 10 "a VOIDED payslip is shown as APPROVED to the employee" \
  "" \
  "PayrollPackageApiIntegrationTest PayslipPdfIntegrationTest PayrollPackagePrivacyIntegrationTest" \
  mutate_voided_as_approved

mutate_expiry() {
  mutate "$SRC/application/PayrollApprovalService.java" \
    '|| !expiresAt.isAfter(now)' \
    '|| false' &&
  mutate "$SRC/application/PayrollVoidService.java" \
    'if (!expiresAt.isAfter(now)' \
    'if (false'
}

run 11 "the preview expiry is ignored (approval and void)" \
  "" \
  "PayrollPackageApiIntegrationTest" \
  mutate_expiry

echo
if [ "${CHECK_ONLY:-}" ]; then
  [ "$survivors" -eq 0 ] && { echo "PASS: every anchor exists exactly once"; exit 0; }
  echo "FAIL: $survivors anchor(s) not applicable"
  exit 1
fi
if [ "$survivors" -ne 0 ]; then
  echo "FAIL: $survivors mutation(s) survived or could not be applied"
  exit 1
fi
if [ "$skipped" -ne 0 ]; then
  echo "PARTIAL: $skipped mutation(s) not evaluated (UNIT_ONLY); the unit-visible ones were killed"
  exit 3
fi
echo "PASS: every mutation was killed"
